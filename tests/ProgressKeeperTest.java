import com.gtamine.mobile.progresskeeper.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ProgressKeeperTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger observed = new AtomicInteger();
        TaskCountListener observer = count -> { if (count == 0) observed.incrementAndGet(); };
        ProgressKeeper.submitProgress("download", 0, 1);
        ProgressKeeper.waitUntilDone(() -> {
            completed.incrementAndGet();
            // Re-enter task notifications while the completion callback is running.
            ProgressKeeper.submitProgress("nested", 0, 1);
            ProgressKeeper.submitProgress("nested", -1, -1);
        });
        ProgressKeeper.addTaskCountListener(observer, false);
        ProgressKeeper.submitProgress("download", -1, -1);
        check(completed.get() == 1, "Completion must run once despite nested notifications");
        check(observed.get() == 2, "Other listeners must receive both completion events");
        ProgressKeeper.removeTaskCountListener(observer);

        AtomicInteger lateCalls = new AtomicInteger();
        TaskCountListener nestedFirst = new TaskCountListener() {
            boolean ran;
            public void onUpdateTaskCount(int count) {
                if (count != 0 || ran) return;
                ran = true;
                ProgressKeeper.submitProgress("before-waiter", 0, 1);
                ProgressKeeper.submitProgress("before-waiter", -1, -1);
            }
        };
        ProgressKeeper.submitProgress("late-waiter", 0, 1);
        ProgressKeeper.addTaskCountListener(nestedFirst, false);
        ProgressKeeper.waitUntilDone(lateCalls::incrementAndGet);
        ProgressKeeper.submitProgress("late-waiter", -1, -1);
        check(lateCalls.get() == 1, "A stale outer snapshot must not invoke a completed waiter again");
        ProgressKeeper.removeTaskCountListener(nestedFirst);

        TaskCountListener selfRemoving = new TaskCountListener() {
            @Override public void onUpdateTaskCount(int count) { ProgressKeeper.removeTaskCountListener(this); }
        };
        ProgressKeeper.addTaskCountListener(selfRemoving);
        ProgressKeeper.submitProgress("other", 0, 1);
        ProgressKeeper.submitProgress("other", -1, -1);

        AtomicInteger progressEnded = new AtomicInteger();
        ProgressListener selfRemovingProgress = new ProgressListener() {
            public void onProgressStarted() { }
            public void onProgressUpdated(int progress, int resid, Object... values) {
                ProgressKeeper.removeListener("progress", this);
            }
            public void onProgressEnded() { }
        };
        ProgressListener next = new ProgressListener() {
            public void onProgressStarted() { }
            public void onProgressUpdated(int progress, int resid, Object... values) { progressEnded.incrementAndGet(); }
            public void onProgressEnded() { }
        };
        ProgressKeeper.addListener("progress", selfRemovingProgress);
        ProgressKeeper.addListener("progress", next);
        ProgressKeeper.submitProgress("progress", 0, 1);
        ProgressKeeper.submitProgress("progress", 50, 1);
        check(progressEnded.get() == 1, "Removing a progress listener must not skip the next listener");
        ProgressKeeper.submitProgress("progress", -1, -1);
        ProgressKeeper.removeListener("progress", next);

        for (int i = 0; i < 500; i++) {
            AtomicInteger calls = new AtomicInteger();
            ProgressKeeper.submitProgress("race", 0, 1);
            Thread waiter = new Thread(() -> ProgressKeeper.waitUntilDone(calls::incrementAndGet));
            Thread finisher = new Thread(() -> ProgressKeeper.submitProgress("race", -1, -1));
            waiter.start(); finisher.start(); waiter.join(); finisher.join();
            check(calls.get() == 1, "Concurrent wait/finish must complete exactly once");
        }
        check(ProgressKeeper.getTaskCount() == 0, "No tasks should remain");
        System.out.println("PASS: self-removal, nested completion, progress listener mutation and 500 concurrent wait/finish races");
    }
}
