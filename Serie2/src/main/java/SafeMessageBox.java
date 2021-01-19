import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class SafeMessageBox<T> {
    private final AtomicReference<MsgHolder> msgHolder = new AtomicReference<>();

    private class MsgHolder {
        private final T msg;
        private final AtomicInteger lives;

        MsgHolder(T msg, int lvs) {
            this.msg = msg;
            this.lives = new AtomicInteger(lvs);
        }
    }

    public void publish(T msg, int lvs) {
        MsgHolder myMsgHolder = new MsgHolder(msg, lvs);
        msgHolder.set(myMsgHolder);
    }

    public Optional<T> tryConsume() {
        MsgHolder observedMsgHolder;
        do {
            observedMsgHolder = msgHolder.get();
            if(observedMsgHolder == null) return Optional.empty();
            int lvs = observedMsgHolder.lives.get();
            if(lvs <= 0) return Optional.empty();
            if(observedMsgHolder.lives.compareAndSet(lvs, lvs - 1)) break;
        } while(true);
        return Optional.of(observedMsgHolder.msg);
    }
}