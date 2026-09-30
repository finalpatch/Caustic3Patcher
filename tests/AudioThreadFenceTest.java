import com.singlecellsoftware.caustic.audio.AudioBackend;
import java.util.concurrent.CountDownLatch;

public final class AudioThreadFenceTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Thread done = new Thread(() -> {});
        done.start();
        AudioBackend.awaitOutputThread(done);
        check(!done.isAlive());

        CountDownLatch release = new CountDownLatch(1);
        Thread blocked = new Thread(() -> {
            try { release.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        blocked.start();
        boolean rejected = false;
        try { AudioBackend.awaitOutputThread(blocked); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected && blocked.isAlive());
        Thread.currentThread().interrupt();
        rejected = false;
        try { AudioBackend.awaitOutputThread(blocked); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected && Thread.currentThread().isInterrupted());
        Thread.interrupted();
        release.countDown();
        AudioBackend.awaitOutputThread(blocked);
        check(!blocked.isAlive());
        System.out.println("PASS: thread exit required; timeout rejects live worker; interruption retained; eventual stop succeeds.");
    }
}
