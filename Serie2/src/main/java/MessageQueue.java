import util.LockFreeQueue;
import util.Timeouts;

import java.util.Optional;

public class MessageQueue<T> {

    private final LockFreeQueue<Message> messages = new LockFreeQueue<>();
    private final LockFreeQueue<Request> requests = new LockFreeQueue<>();

    private class Message implements SendStatus {
        final T message;
        boolean isDone = false;

        Message(T message) {
            this.message = message;
        }

        @Override
        public boolean isSent() {
            return isDone;
        }

        @Override
        public boolean await(int timeout) {
            // Happy Path
            if(isDone) return true;

            if(Timeouts.noWait(timeout)) return false;

            long limit = Timeouts.start(timeout);
            long remaining = Timeouts.remaining(limit);
            while(!Timeouts.isTimeout(remaining) && !isDone)
                remaining = Timeouts.remaining(limit);
            return isDone;
        }
    }

    private class Request {
        T message;
        boolean isTimeout = false;
    }

    public SendStatus send(T sentMsg) {
        Message msg = new Message(sentMsg);
        // Happy path
        while(!requests.isEmpty()) {
            Request req = requests.dequeue();
            if(req != null && !req.isTimeout) {
                req.message = sentMsg;
                msg.isDone = true;
                return msg;
            }
        }

        // Enqueue message
        messages.enqueue(msg);
        return msg;
    }

    public Optional<T> receive(int timeout) {
        // Happy path - no wait
        while(!messages.isEmpty()) {
            Message msg = messages.dequeue();
            if(msg != null) {
                msg.isDone = true;
                return Optional.of(msg.message);
            }
        }

        // If we can't wait just return an empty Optional
        if(Timeouts.noWait(timeout)) return Optional.empty();

        // Otherwise lets wait (actively) for the Message
        final Request req = new Request();
        requests.enqueue(req);

        final long limit = Timeouts.start(timeout);
        long remaining = Timeouts.remaining(limit);
        while(!Timeouts.isTimeout(remaining) && req.message == null)
            remaining = Timeouts.remaining(limit);
        req.isTimeout = true;
        return req.message == null ? Optional.empty() : Optional.of(req.message);
    }
}
