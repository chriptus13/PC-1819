import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

public class LockFreeDualQueue<T> {
    // types of queue nodes
    private enum NodeType {
        DATUM, REQUEST
    }

    // the queue node
    private static class QNode<T> {
        NodeType type;
        final T data;
        final AtomicReference<QNode<T>> request;
        final AtomicReference<QNode<T>> next;

        //  build a datum or request node
        QNode(T d, NodeType t) {
            type = t;
            data = d;
            request = new AtomicReference<>(null);
            next = new AtomicReference<>(null);
        }
    }

    // the head and tail references
    private final AtomicReference<QNode<T>> head;
    private final AtomicReference<QNode<T>> tail;

    public LockFreeDualQueue() {
        QNode<T> sentinel = new QNode<>(null, NodeType.DATUM);
        head = new AtomicReference<>(sentinel);
        tail = new AtomicReference<>(sentinel);
    }

    // enqueue a datum
    public void enqueue(T v) {
        QNode<T> n = new QNode<>(v, NodeType.DATUM);
        while(true) {
            QNode<T> observedTail = tail.get(), observedHead = head.get();
            if(observedTail == observedHead || observedTail.type != NodeType.REQUEST) {
                QNode<T> next = observedTail.next.get();
                if(observedTail == tail.get()) {
                    if(next != null) tail.compareAndSet(observedTail, next);
                    else {
                        if(observedTail.next.compareAndSet(null, n)) {
                            tail.compareAndSet(observedTail, n);
                            return;
                        }
                    }
                }
            } else {
                QNode<T> next = observedHead.next.get();
                if(observedTail == tail.get()) {
                    QNode<T> req = observedHead.request.get();
                    if(observedHead == head.get()) {
                        boolean success = req == null && observedHead.request.compareAndSet(null, n);
                        head.compareAndSet(observedHead, next);
                        if(success) return;
                    }
                }
            }
        }
    }

    // dequeue a datum - spinning if necessary
    public T dequeue() throws InterruptedException {
        QNode<T> h, hnext, t, tnext, n = null;
        do {
            h = head.get();
            t = tail.get();

            if(t == h || t.type == NodeType.REQUEST) {
                // queue empty, tail falling behind, or queue contains data (queue could also
                // contain exactly one outstanding request with tail pointer as yet unswung)
                tnext = t.next.get();

                if(t == tail.get()) {        // tail and next are consistent
                    if(tnext != null) {    // tail falling behind
                        tail.compareAndSet(t, tnext);
                    } else {    // try to link in a request for data
                        if(n == null) {
                            n = new QNode<>(null, NodeType.REQUEST);
                        }
                        if(t.next.compareAndSet(null, n)) {
                            // linked in request; now try to swing tail pointer
                            tail.compareAndSet(t, n);

                            // help someone else if I need to
                            if(h == head.get() && h.request.get() != null) {
                                head.compareAndSet(h, h.next.get());
                            }

                            // busy waiting for a data done.
                            // we use sleep instead od yield in order to accept interrupts
                            while(t.request.get() == null) {
                                Thread.sleep(0);  // spin accepting interrupts!!!
                            }

                            // help snip my node
                            h = head.get();
                            if(h == t) {
                                head.compareAndSet(h, n);
                            }

                            // data is now available; read it out and go home
                            return t.request.get().data;
                        }
                    }
                }
            } else {    // queue consists of real data
                hnext = h.next.get();
                if(t == tail.get()) {
                    // head and next are consistent; read result *before* swinging head
                    T result = hnext.data;
                    if(head.compareAndSet(h, hnext)) {
                        return result;
                    }
                }
            }
        } while(true);
    }

    public boolean isEmpty() {
        // empty or queue consists of requests
        return head.get() == tail.get() || tail.get().type == NodeType.REQUEST;
    }

    /**
     * Test dual queue to drive in a producer/consumer context.
     */

    public static boolean testLockFreeDualQueue() throws InterruptedException {
        final int CONSUMER_THREADS = 2;
        final int PRODUCER_THREADS = 1;
        final int MAX_PRODUCE_INTERVAL = 25;
        final int MAX_CONSUME_TIME = 25;
        final int FAILURE_PERCENT = 5;
        final int JOIN_TIMEOUT = 100;
        final int RUN_TIME = 10 * 1000;
        final int POLL_INTERVAL = 20;

        Thread[] consumers = new Thread[CONSUMER_THREADS];
        Thread[] producers = new Thread[PRODUCER_THREADS];
        final LockFreeDualQueue<String> dqueue = new LockFreeDualQueue<String>();
        final int[] productions = new int[PRODUCER_THREADS];
        final int[] consumptions = new int[CONSUMER_THREADS];
        final int[] failuresInjected = new int[PRODUCER_THREADS];
        final int[] failuresDetected = new int[CONSUMER_THREADS];

        // create and start the consumer threads.
        for(int i = 0; i < CONSUMER_THREADS; i++) {
            final int tid = i;
            consumers[i] = new Thread(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                int count = 0;

                System.out.printf("-->c#%02d starts...%n", tid);
                do {
                    try {
                        String data = dqueue.dequeue();
                        if(!data.equals("hello")) {
                            failuresDetected[tid]++;
                            System.out.printf("[f#%d]", tid);
                        }

                        if(++count % 100 == 0)
                            System.out.printf("[c#%02d]", tid);

                        // simulate the time needed to process the data.
                        Thread.sleep(rnd.nextInt(MAX_CONSUME_TIME + 1));

                    } catch(InterruptedException ie) {
                        //do {} while (tid == 0);
                        break;
                    }
                } while(true);

                // display consumer thread's results.
                System.out.printf("%n<--c#%02d exits, consumed: %d, failures: %d",
                        tid, count, failuresDetected[tid]);
                consumptions[tid] = count;
            });
            consumers[i].setDaemon(true);
            consumers[i].start();
        }

        // create and start the producer threads.
        for(int i = 0; i < PRODUCER_THREADS; i++) {
            final int tid = i;
            producers[i] = new Thread(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                int count = 0;

                System.out.printf("-->p#%02d starts...%n", tid);
                do {
                    String data;

                    if(rnd.nextInt(100) >= FAILURE_PERCENT) {
                        data = "hello";
                    } else {
                        data = "HELLO";
                        failuresInjected[tid]++;
                    }

                    // enqueue a data item
                    dqueue.enqueue(data);

                    // Increment request count and periodically display the "alive" menssage.
                    if(++count % 100 == 0)
                        System.out.printf("[p#%02d]", tid);

                    // production interval.
                    try {
                        Thread.sleep(rnd.nextInt(MAX_PRODUCE_INTERVAL));
                    } catch(InterruptedException ie) {
                        //do {} while (tid == 0);
                        break;
                    }
                } while(true);
                System.out.printf("%n<--p#%02d exits, produced: %d, failures: %d",
                        tid, count, failuresInjected[tid]);
                productions[tid] = count;
            });
            producers[i].setDaemon(true);
            producers[i].start();
        }

        // run the test RUN_TIME milliseconds
        Thread.sleep(RUN_TIME);

        // interrupt all producer threads and wait for until each one finished.
        int stillRunning = 0;
        for(int i = 0; i < PRODUCER_THREADS; i++) {
            producers[i].interrupt();
            producers[i].join(JOIN_TIMEOUT);
            if(producers[i].isAlive())
                stillRunning++;
        }

        // wait until the queue is empty
        while(!dqueue.isEmpty())
            Thread.sleep(POLL_INTERVAL);

        // Interrupt each consumer thread and wait for a while until each one finished.
        for(int i = 0; i < CONSUMER_THREADS; i++) {
            consumers[i].interrupt();
            consumers[i].join(JOIN_TIMEOUT);
            if(consumers[i].isAlive())
                stillRunning++;
        }

        // If any thread failed to fisnish, something is wrong.
        if(stillRunning > 0) {
            System.out.printf("%n<--*** failure: %d thread(s) did answer to interrupt%n", stillRunning);
            return false;
        }

        // Compute and display the results.

        long sumProductions = 0, sumFailuresInjected = 0;
        for(int i = 0; i < PRODUCER_THREADS; i++) {
            sumProductions += productions[i];
            sumFailuresInjected += failuresInjected[i];
        }
        long sumConsumptions = 0, sumFailuresDetected = 0;
        for(int i = 0; i < CONSUMER_THREADS; i++) {
            sumConsumptions += consumptions[i];
            sumFailuresDetected += failuresDetected[i];
        }
        System.out.printf("%n<-- successful: %d/%d, failed: %d/%d%n",
                sumProductions, sumConsumptions, sumFailuresInjected, sumFailuresDetected);

        return sumProductions == sumConsumptions && sumFailuresInjected == sumFailuresDetected;
    }

    public static void main(String[] args) throws Throwable {
        System.out.printf("%n--> Test lock free dual queue: %s%n",
                (testLockFreeDualQueue() ? "passed" : "failed"));
    }
}
