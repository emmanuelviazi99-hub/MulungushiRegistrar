package zm.ac.mulungushi.registrar;

import android.os.CountDownTimer;

/** The 30 second wait before "Resend code" turns on. */
final class ResendTimer {

    interface Listener {
        void onTick(int secondsLeft);

        void onFinish();
    }

    private CountDownTimer timer;

    void start(int seconds, Listener listener) {
        cancel();
        if (seconds <= 0) {
            listener.onFinish();
            return;
        }
        listener.onTick(seconds);
        timer = new CountDownTimer(seconds * 1000L, 1000L) {
            @Override
            public void onTick(long millisLeft) {
                listener.onTick((int) Math.ceil(millisLeft / 1000.0));
            }

            @Override
            public void onFinish() {
                listener.onFinish();
            }
        }.start();
    }

    void cancel() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** 27 becomes "0:27". */
    static String clock(int seconds) {
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
    }
}
