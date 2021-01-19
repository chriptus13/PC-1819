import util.NodeLinkedList;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class EventBus {
    private final Lock monitor = new ReentrantLock();
    private final int maxPending;
    private final Map<Class, NodeLinkedList<Handler>> map = new HashMap<>();

    // State to allow shutting down
    private boolean shuttingDown = false;
    private int runningHandlers = 0;
    private boolean done = false;
    private final Condition isDone = monitor.newCondition();

    public EventBus(int maxPending) {
        this.maxPending = maxPending;
    }

    private static class Handler<T> {
        final Condition condition;
        final NodeLinkedList<T> messages = new NodeLinkedList<>();

        Handler(Condition condition) {
            this.condition = condition;
        }

    }

    public <T> void subscribeEvent(Consumer<T> handle, Class<T> classT) throws InterruptedException {
        // Register handler
        final Handler<T> handler = new Handler<>(monitor.newCondition());
        NodeLinkedList.Node<Handler> node = registerHandler(handler, classT);

        // Await and execute
        Optional<T> msg;
        while(true) {
            msg = getOrWait(handler);
            if(msg.isPresent()) {
                handle.accept(msg.get());
            } else { // If no more messages just exit
                if(runningHandlers == 0) finish();  // If last handler exiting just notify anyone waiting for shut down
                break;
            }
        }
        removeHandler(node, classT);
    }

    public <E> void publishEvent(E message) {
        try {
            monitor.lock();
            // Fast Path
            if(shuttingDown) throw new IllegalStateException("Event Bus is shutting down!");

            final Class clazz = message.getClass();
            final NodeLinkedList<Handler> handlers = map.get(clazz);

            // If there are no subscribers to this type of events discard the message
            if(handlers == null) return;

            handlers.forEach(handler -> {
                if(handler.messages.size() < maxPending) {
                    handler.messages.push(message);
                    handler.condition.signal();
                }
            });
        } finally {
            monitor.unlock();
        }
    }

    public void shutdown() throws InterruptedException {
        try {
            monitor.lock();
            if(!shuttingDown) {
                for(NodeLinkedList<Handler> handlerList : map.values())
                    handlerList.forEach(handler -> handler.condition.signal());
                shuttingDown = true;
            }
            while(runningHandlers > 0 && !done) isDone.await();
        } finally {
            monitor.unlock();
        }
    }

    private <T> NodeLinkedList.Node<Handler> registerHandler(Handler<T> handler, Class<T> classT) {
        try {
            monitor.lock();
            runningHandlers += 1;   // Handler working
            final NodeLinkedList<Handler> event = map.computeIfAbsent(classT, key -> new NodeLinkedList<>());
            return event.push(handler);
        } finally {
            monitor.unlock();
        }
    }

    private <T> void removeHandler(NodeLinkedList.Node<Handler> node, Class<T> classT) {
        try {
            monitor.lock();
            map.get(classT).remove(node);
        } finally {
            monitor.unlock();
        }
    }

    private <T> Optional<T> getOrWait(Handler handler) throws InterruptedException {
        try {
            monitor.lock();
            while(true) {

                // If there are still messages to process just do it
                if(!handler.messages.isEmpty()) {
                    return Optional.of((T) handler.messages.pull().value);
                }
                // Otherwise if is shutting down let handler finish
                if(shuttingDown) {
                    runningHandlers -= 1;
                    return Optional.empty();
                }
                // If no messages and no shut down just wait for a message
                try {
                    handler.condition.await();
                } catch(InterruptedException e) {
                    // If interrupted just ignore any message
                    runningHandlers -= 1;
                    throw e;
                }
            }
        } finally {
            monitor.unlock();
        }
    }

    // Method to be called by last Handler on exiting
    private void finish() {
        try {
            monitor.lock();
            done = true;
            isDone.signal();
        } finally {
            monitor.unlock();
        }
    }
}
