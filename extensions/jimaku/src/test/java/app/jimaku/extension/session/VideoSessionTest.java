package app.jimaku.extension.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import app.jimaku.extension.gateway.Cancellation;
import app.jimaku.extension.gateway.GatewayException;
import app.jimaku.extension.gateway.Lookup;
import app.jimaku.extension.gateway.Reads;
import app.jimaku.extension.subtitles.Line;

public class VideoSessionTest {
    private static final String FIRST = "aaaaaaaaaaa";
    private static final String SECOND = "bbbbbbbbbbb";

    /** Runs the background work only when the test says so, as a slow gateway would answer. */
    private static final class Delayed implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        void runNext() {
            queue.poll().run();
        }
    }

    private static final class FakeReads implements Reads {
        final Map<String, Lookup> lookups = new HashMap<>();
        final Map<String, List<Line>> documents = new HashMap<>();
        final List<String> requests = new ArrayList<>();
        final List<Cancellation> cancellations = new ArrayList<>();
        String failWith;

        @Override
        public Lookup lookup(String video, Cancellation cancellation) throws GatewayException {
            requests.add("lookup " + video);
            cancellations.add(cancellation);
            if (failWith != null) throw new GatewayException(failWith);
            return lookups.get(video);
        }

        @Override
        public List<Line> document(String video, String lang, Cancellation cancellation) {
            requests.add("document " + video + " " + lang);
            return documents.get(video + "/" + lang);
        }

        @Override
        public List<Line> original(String video, Cancellation cancellation) {
            requests.add("original " + video);
            return documents.get(video + "/original");
        }
    }

    private static final class Recorder implements VideoSession.Display {
        final List<String> events = new ArrayList<>();
        final List<String> lookups = new ArrayList<>();

        @Override
        public void lookedUp(String video, Lookup lookup) {
            lookups.add(video + " " + (lookup == null ? "nothing" : lookup.cachedLanguages()));
        }

        @Override
        public void show(String video, String lang, List<Line> lines) {
            events.add(lang == null ? "hide " + video : "show " + video + " " + lang + " " + lines.get(0).text);
        }

        @Override
        public void original(String video, List<Line> lines) {
            events.add("original " + video + " " + lines.get(0).text);
        }

        @Override
        public void clear() {
            events.add("clear");
        }
    }

    private final Delayed background = new Delayed();
    private final FakeReads reads = new FakeReads();
    private final Recorder display = new Recorder();
    private final VideoSession session = new VideoSession(reads, background, Runnable::run, display);

    private void cache(String video, String original, String lang, String text) {
        reads.lookups.put(video, new Lookup(original, Collections.singletonList(lang), false));
        reads.documents.put(video + "/" + lang, Collections.singletonList(new Line(0, 2, text)));
    }

    private void document(String video, String lang, String text) {
        reads.documents.put(video + "/" + lang, Collections.singletonList(new Line(0, 2, text)));
    }

    /** FIRST is Japanese, cached in German and English. */
    private void cacheBoth() {
        reads.lookups.put(FIRST, new Lookup("ja", List.of("de", "en"), false));
        document(FIRST, "de", "Hallo");
        document(FIRST, "en", "Hello");
        document(FIRST, "original", "こんにちは");
    }

    @Test
    public void showsTheSubtitlesInTheViewersLanguage() {
        cache(FIRST, "ja", "de", "Hallo");
        session.open(FIRST, "de", true);
        background.runNext();
        assertEquals(List.of("clear", "show aaaaaaaaaaa de Hallo"), display.events);
        assertEquals(List.of("lookup aaaaaaaaaaa", "document aaaaaaaaaaa de"), reads.requests);
    }

    @Test
    public void showsTheTranscriptForAVideoInTheViewersLanguage() {
        reads.lookups.put(FIRST, new Lookup("de", Collections.singletonList("en"), false));
        reads.documents.put(FIRST + "/de", Collections.singletonList(new Line(0, 2, "Moin")));
        session.open(FIRST, "de", true);
        background.runNext();
        assertEquals("show aaaaaaaaaaa de Moin", display.events.get(display.events.size() - 1));
    }

    @Test
    public void dropsALateAnswerForTheFirstVideo() {
        cache(FIRST, "ja", "de", "first");
        cache(SECOND, "ja", "de", "second");
        session.open(FIRST, "de", true);
        session.open(SECOND, "de", true);

        background.runNext();
        assertTrue("the first video's reads are cancelled", reads.cancellations.get(0).isCancelled());
        assertEquals(List.of("clear", "clear"), display.events);

        background.runNext();
        assertEquals(List.of("clear", "clear", "show bbbbbbbbbbb de second"), display.events);
    }

    @Test
    public void aNewVideoRemovesTheOldSubtitlesFirst() {
        cache(FIRST, "ja", "de", "first");
        session.open(FIRST, "de", true);
        background.runNext();
        session.reset();
        assertEquals(List.of("clear", "show aaaaaaaaaaa de first", "clear"), display.events);
        assertEquals(null, session.video());
    }

    @Test
    public void anAnswerAfterResetNeverShows() {
        cache(FIRST, "ja", "de", "first");
        session.open(FIRST, "de", true);
        session.reset();
        background.runNext();
        assertEquals(List.of("clear", "clear"), display.events);
    }

    @Test
    public void doesNotOfferTheOriginalOfAMixedTranscript() {
        reads.lookups.put(FIRST, new Lookup("ja", Collections.singletonList("en"), true));
        reads.documents.put(FIRST + "/ja", Collections.singletonList(new Line(0, 2, "mixed")));
        session.open(FIRST, "ja", true);
        background.runNext();
        assertEquals(List.of("clear"), display.events);
        assertEquals(List.of("lookup aaaaaaaaaaa"), reads.requests);
    }

    @Test
    public void showsNothingWhenNotCachedOrNotInTheLanguage() {
        session.open(FIRST, "de", true);
        background.runNext();
        cache(SECOND, "ja", "fr", "bonjour");
        session.open(SECOND, "de", true);
        background.runNext();
        assertEquals(List.of("clear", "clear"), display.events);
        assertEquals(List.of("lookup aaaaaaaaaaa", "lookup bbbbbbbbbbb"), reads.requests);
    }

    @Test
    public void aFailedReadShowsNothingAndTheNextVideoTriesAgain() {
        reads.failWith = "offline";
        session.open(FIRST, "de", true);
        background.runNext();
        assertEquals(List.of("clear"), display.events);

        reads.failWith = null;
        cache(SECOND, "ja", "de", "second");
        session.open(SECOND, "de", true);
        background.runNext();
        assertEquals("show bbbbbbbbbbb de second", display.events.get(display.events.size() - 1));
    }

    @Test
    public void opensTheSameVideoOnce() {
        cache(FIRST, "ja", "de", "first");
        session.open(FIRST, "de", true);
        session.open(FIRST, "de", true);
        assertEquals(1, background.queue.size());
    }

    @Test
    public void withSubtitlesOffOnlyLooksUp() {
        cacheBoth();
        session.open(FIRST, "de", false);
        background.runNext();
        assertEquals(List.of("clear"), display.events);
        assertEquals(List.of("lookup aaaaaaaaaaa"), reads.requests);
        assertEquals(List.of("aaaaaaaaaaa [ja, de, en]"), display.lookups);
        assertEquals(null, session.wanted());
    }

    @Test
    public void choosingALanguageShowsItAndShowingItAgainReadsNothing() {
        cacheBoth();
        session.open(FIRST, "de", false);
        background.runNext();
        session.choose("en");
        background.runNext();
        assertEquals("en", session.showing());
        session.hide();
        assertEquals(null, session.showing());
        session.choose("en");
        assertEquals(0, background.queue.size());
        assertEquals(List.of("clear", "show aaaaaaaaaaa en Hello", "hide aaaaaaaaaaa", "show aaaaaaaaaaa en Hello"),
                display.events);
        assertEquals(List.of("lookup aaaaaaaaaaa", "document aaaaaaaaaaa en"), reads.requests);
    }

    @Test
    public void theLatestPressWins() {
        cacheBoth();
        session.open(FIRST, "de", false);
        background.runNext();
        session.choose("en");
        session.choose("de");
        background.runNext();
        background.runNext();
        assertEquals("de", session.showing());
        assertEquals(List.of("clear", "show aaaaaaaaaaa de Hallo"), display.events);
    }

    @Test
    public void hidingDropsAReadOnItsWay() {
        cacheBoth();
        session.open(FIRST, "de", false);
        background.runNext();
        session.choose("en");
        session.hide();
        background.runNext();
        assertEquals(null, session.showing());
        assertEquals(List.of("clear"), display.events);
        session.choose("en");
        assertEquals("the document was kept", 0, background.queue.size());
        assertEquals("show aaaaaaaaaaa en Hello", display.events.get(display.events.size() - 1));
    }

    @Test
    public void choosesOnlyWhatTheCacheHas() {
        cacheBoth();
        session.choose("de");
        session.open(FIRST, "de", false);
        session.choose("de");
        assertEquals("nothing before the lookup answers", 1, background.queue.size());
        background.runNext();
        session.choose("fr");
        assertEquals(0, background.queue.size());
    }

    @Test
    public void readsTheOriginalOnce() {
        cacheBoth();
        session.open(FIRST, "de", true);
        background.runNext();
        session.askOriginal();
        session.askOriginal();
        background.runNext();
        session.askOriginal();
        assertEquals(0, background.queue.size());
        assertEquals("こんにちは", session.original().get(0).text);
        assertEquals(List.of("clear", "show aaaaaaaaaaa de Hallo", "original aaaaaaaaaaa こんにちは"), display.events);
        assertEquals(List.of("lookup aaaaaaaaaaa", "document aaaaaaaaaaa de", "original aaaaaaaaaaa"), reads.requests);
    }

    @Test
    public void aNewVideoDropsTheOriginalOnItsWay() {
        cacheBoth();
        cache(SECOND, "ja", "de", "second");
        session.open(FIRST, "de", false);
        background.runNext();
        session.askOriginal();
        session.open(SECOND, "de", false);
        background.runNext();
        assertEquals(null, session.original());
        assertEquals(List.of("clear", "clear"), display.events);
    }

    @Test
    public void ignoresSomethingThatIsNotAVideoId() {
        session.open("not an id", "de", true);
        assertEquals(0, background.queue.size());
        assertEquals(null, session.video());
    }
}
