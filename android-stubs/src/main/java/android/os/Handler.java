package android.os;

@android.annotation.Stub
public class Handler {
    public Handler() {}

    public Handler(Looper looper) {}

    public boolean post(Runnable r) {
        try {
            new Thread(r).start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean postDelayed(Runnable r, long delayMillis) {
        try {
            new Thread(() -> {
                try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) {}
                r.run();
            }).start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
