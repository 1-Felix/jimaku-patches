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
    }

    private static final class Recorder implements VideoSession.Display {
        final List<String> events = new ArrayList<>();

        @Override
        public void show(String video, String lang, List<Line> lines) {
            events.add("show " + video + " " + lang + " " + lines.get(0).text);
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

    @Test
    public void showsTheSubtitlesInTheViewersLanguage() {
        cache(FIRST, "ja", "de", "Hallo");
        session.open(FIRST, "de");
        background.runNext();
        assertEquals(List.of("clear", "show aaaaaaaaaaa de Hallo"), display.events);
        assertEquals(List.of("lookup aaaaaaaaaaa", "document aaaaaaaaaaa de"), reads.requests);
    }

    @Test
    public void showsTheTranscriptForAVideoInTheViewersLanguage() {
        reads.lookups.put(FIRST, new Lookup("de", Collections.singletonList("en"), false));
        reads.documents.put(FIRST + "/de", Collections.singletonList(new Line(0, 2, "Moin")));
        session.open(FIRST, "de");
        background.runNext();
        assertEquals("show aaaaaaaaaaa de Moin", display.events.get(display.events.size() - 1));
    }

    @Test
    public void dropsALateAnswerForTheFirstVideo() {
        cache(FIRST, "ja", "de", "first");
        cache(SECOND, "ja", "de", "second");
        session.open(FIRST, "de");
        session.open(SECOND, "de");

        background.runNext();
        assertTrue("the first video's reads are cancelled", reads.cancellations.get(0).isCancelled());
        assertEquals(List.of("clear", "clear"), display.events);

        background.runNext();
        assertEquals(List.of("clear", "clear", "show bbbbbbbbbbb de second"), display.events);
    }

    @Test
    public void aNewVideoRemovesTheOldSubtitlesFirst() {
        cache(FIRST, "ja", "de", "first");
        session.open(FIRST, "de");
        background.runNext();
        session.reset();
        assertEquals(List.of("clear", "show aaaaaaaaaaa de first", "clear"), display.events);
        assertEquals(null, session.video());
    }

    @Test
    public void anAnswerAfterResetNeverShows() {
        cache(FIRST, "ja", "de", "first");
        session.open(FIRST, "de");
        session.reset();
        background.runNext();
        assertEquals(List.of("clear", "clear"), display.events);
    }

    @Test
    public void doesNotOfferTheOriginalOfAMixedTranscript() {
        reads.lookups.put(FIRST, new Lookup("ja", Collections.singletonList("en"), true));
        reads.documents.put(FIRST + "/ja", Collections.singletonList(new Line(0, 2, "mixed")));
        session.open(FIRST, "ja");
        background.runNext();
        assertEquals(List.of("clear"), display.events);
        assertEquals(List.of("lookup aaaaaaaaaaa"), reads.requests);
    }

    @Test
    public void showsNothingWhenNotCachedOrNotInTheLanguage() {
        session.open(FIRST, "de");
        background.runNext();
        cache(SECOND, "ja", "fr", "bonjour");
        session.open(SECOND, "de");
        background.runNext();
        assertEquals(List.of("clear", "clear"), display.events);
        assertEquals(List.of("lookup aaaaaaaaaaa", "lookup bbbbbbbbbbb"), reads.requests);
    }

    @Test
    public void aFailedReadShowsNothingAndTheNextVideoTriesAgain() {
        reads.failWith = "offline";
        session.open(FIRST, "de");
        background.runNext();
        assertEquals(List.of("clear"), display.events);

        reads.failWith = null;
        cache(SECOND, "ja", "de", "second");
        session.open(SECOND, "de");
        background.runNext();
        assertEquals("show bbbbbbbbbbb de second", display.events.get(display.events.size() - 1));
    }

    @Test
    public void opensTheSameVideoOnce() {
        cache(FIRST, "ja", "de", "first");
        session.open(FIRST, "de");
        session.open(FIRST, "de");
        assertEquals(1, background.queue.size());
    }

    @Test
    public void ignoresSomethingThatIsNotAVideoId() {
        session.open("not an id", "de");
        assertEquals(0, background.queue.size());
        assertEquals(null, session.video());
    }
}
