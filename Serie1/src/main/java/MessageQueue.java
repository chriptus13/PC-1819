import util.NodeLinkedList;
import util.Timeouts;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MessageQueue<T> {
    private final Lock monitor = new ReentrantLock();

    private final NodeLinkedList<Message> messages = new NodeLinkedList<>();
    private final NodeLinkedList<Request> requests = new NodeLinkedList<>();

    private class Message implements SendStatus {
        final Condition condition;
        final T message;
        boolean isDone = false;
        NodeLinkedList.Node<Message> node;

        Message(T message, Condition condition) {
            this.message = message;
            this.condition = condition;
        }

        @Override
        public boolean isSent() {
            try {
                monitor.lock();
                return isDone;
            } finally {
                monitor.unlock();
            }
        }

        @Override
        public boolean tryCancel() {
            try {
                monitor.lock();
                if(isDone) return false;
                if(node != null) {
                    messages.remove(node);
                    return true;
                }
                return false;
            } finally {
                monitor.unlock();
            }
        }

        @Override
        public boolean await(int timeout) throws InterruptedException {
            try {
                monitor.lock();
                // Happy Path
                if(isDone) return true;

                if(Timeouts.noWait(timeout)) return false;

                long limit = Timeouts.start(timeout);
                long remaining = Timeouts.remaining(limit);
                for(; !Timeouts.isTimeout(remaining); remaining = Timeouts.remaining(limit)) {
                    condition.await(remaining, TimeUnit.MILLISECONDS);
                    if(isDone) return true;
                }
                return false;
            } finally {
                monitor.unlock();
            }
        }
    }

    private class Request {
        final Condition condition;
        T message;
        boolean isDone = false;

        Request(Condition condition) {
            this.condition = condition;
        }
    }

    public SendStatus send(T sentMsg) {
        try {
            monitor.lock();
            // If there are no requests just place the message in the queue
            if(requests.isEmpty()) {
                final NodeLinkedList.Node<Message> node = messages.push(new Message(sentMsg, monitor.newCondition()));
                node.value.node = node;
                return node.value;
            }

            // Otherwise get the first requester and place the message on the requester and signal it
            final Message msg = new Message(sentMsg, monitor.newCondition());
            msg.isDone = true;
            final Request req = requests.pull().value;
            req.message = sentMsg;
            req.isDone = true;
            req.condition.signal();

            return msg;
        } finally {
            monitor.unlock();
        }
    }

    public Optional<T> receive(int timeout) throws InterruptedException {
        try {
            monitor.lock();
            // Happy Path
            if(!messages.isEmpty()) {
                final Message msg = messages.pull().value;
                msg.isDone = true;
                msg.condition.signalAll();
                return Optional.of(msg.message);
            }

            // If we can't wait just return an empty Optional
            if(Timeouts.noWait(timeout)) return Optional.empty();

            // Otherwise lets wait for the Message
            final Request req = new Request(monitor.newCondition());
            final NodeLinkedList.Node<Request> node = requests.push(req);

            final long limit = Timeouts.start(timeout);
            long remaining = Timeouts.remaining(limit);
            for(; !Timeouts.isTimeout(remaining); remaining = Timeouts.remaining(limit)) {
                try {
                    req.condition.await(remaining, TimeUnit.MILLISECONDS);
                } catch(InterruptedException e) {
                    if(req.isDone) {
                        Thread.currentThread().interrupt();
                        return Optional.of(req.message);
                    }
                    requests.remove(node);
                    throw e;
                }
                if(req.isDone) return Optional.of(req.message);
            }
            return Optional.empty();
        } finally {
            monitor.unlock();
        }
    }
}
