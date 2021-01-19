package util;

import java.util.concurrent.atomic.AtomicReference;

public class LockFreeQueue<T> {

    static class Node<T> {
        final AtomicReference<T> value;
        final AtomicReference<Node<T>> next = new AtomicReference<>();

        public Node(T value) {
            this.value = new AtomicReference<>(value);
        }
    }

    private final AtomicReference<Node<T>> head;
    private final AtomicReference<Node<T>> tail;

    public LockFreeQueue() {
        Node<T> dummy = new Node<>(null);
        head = new AtomicReference<>(dummy);
        tail = new AtomicReference<>(dummy);
    }

    public void enqueue(T value) {
        Node<T> node = new Node<>(value);

        while(true) {
            Node<T> observedTail = tail.get();
            Node<T> observedTailNext = observedTail.next.get();
            if(observedTailNext != null) tail.compareAndSet(observedTail, observedTailNext);
            else if(observedTail.next.compareAndSet(null, node)) {
                tail.compareAndSet(observedTail, node);
                break;
            }
        }
    }

    public T dequeue() {
        while(true) {
            Node<T> observedHead = head.get();
            // Happy path - steals value from another thread in middle of dequeue
            T value = observedHead.value.get();
            if(value != null) {
                if(observedHead.value.compareAndSet(value, null)) return value;
                else continue;
            }

            Node<T> observedHeadNext = observedHead.next.get();
            if(observedHeadNext != null) {
                // Advances head
                head.compareAndSet(observedHead, observedHeadNext);
                value = observedHeadNext.value.get();
                if(observedHeadNext.value.compareAndSet(value, null)) {
                    return value;
                }
            } else {
                return null;
            }
        }
    }

    public boolean isEmpty() {
        return head.get() == tail.get();
    }

}