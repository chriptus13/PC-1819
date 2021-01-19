import util.NodeLinkedList;
import util.Timeouts;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SimpleThreadPoolExecuter {
    private final int maxPoolSize, keepAliveTime;
    private int currentPoolSize = 0;

    private final Lock monitor = new ReentrantLock();

    private boolean shuttingDown = false;
    private boolean done = false;
    private final Condition doneSignal = monitor.newCondition();

    private final NodeLinkedList<Condition> waitingPool = new NodeLinkedList<>();
    private final NodeLinkedList<Task> taskQueue = new NodeLinkedList<>();


    private static class Task {
        final Condition condition;
        final Runnable command;
        boolean delivered = false;

        Task(Condition condition, Runnable command) {
            this.condition = condition;
            this.command = command;
        }
    }

    public SimpleThreadPoolExecuter(int maxPoolSize, int keepAliveTime) {
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
    }

    public boolean execute(Runnable command, int timeout) throws InterruptedException {
        try {
            monitor.lock();
            if(command == null) throw new IllegalArgumentException("Command can't be null!");
            if(shuttingDown) throw new RejectedExecutionException("Pool is shutting down!");

            // Happy Path -- if waitingPool is empty and currentPoolSize<maxPoolSize create new Thread
            if(waitingPool.isEmpty()) {
                if(currentPoolSize < maxPoolSize) {
                    createNewWorker(command);
                    return true;
                }
            } else if(taskQueue.isEmpty()) { // If waitingPool is not empty put the task in the list and signal worker
                taskQueue.push(new Task(monitor.newCondition(), command));
                waitingPool.pull().value.signal();
                return true;
            }

            // Check if we can wait
            if(Timeouts.noWait(timeout)) return false;

            // Place task in queue
            final Task task = new Task(monitor.newCondition(), command);
            final NodeLinkedList.Node<Task> node = taskQueue.push(task);

            final long limit = Timeouts.start(timeout);
            long remaining = Timeouts.remaining(limit);
            for(; !Timeouts.isTimeout(remaining); remaining = Timeouts.remaining(limit)) {
                try {
                    task.condition.await(remaining, TimeUnit.MILLISECONDS);
                } catch(InterruptedException e) {
                    if(task.delivered) {
                        Thread.currentThread().interrupt();
                        return true;
                    }
                    taskQueue.remove(node);
                    throw e;
                }
                if(task.delivered) return true;
            }

            return false;
        } finally {
            monitor.unlock();
        }
    }

    public void shutdown() {
        try {
            monitor.lock();
            if(!shuttingDown) shuttingDown = true;
        } finally {
            monitor.unlock();
        }
    }

    public boolean awaitTermination(int timeout) throws InterruptedException {
        try {
            monitor.lock();
            if(done) return true;
            if(Timeouts.noWait(timeout)) return false;

            long limit = Timeouts.start(timeout);
            long remaining = Timeouts.remaining(limit);
            for(; !Timeouts.isTimeout(remaining); remaining = Timeouts.remaining(limit))
                doneSignal.await(remaining, TimeUnit.MILLISECONDS);
            return false;
        } finally {
            monitor.unlock();
        }
    }

    private boolean isWork() {
        return !taskQueue.isEmpty();
    }

    /// Waits timeout on the condition and tries to get work
    private Optional<Task> getOrWait(Condition condition, long timeOut) throws InterruptedException {
        try {
            monitor.lock();
            do {
                // If there is work leave waiting queue so task can be processed
                if(isWork()) {
                    Task task = taskQueue.pull().value;
                    // Signal command delivery
                    task.delivered = true;
                    task.condition.signal();
                    return Optional.of(task);
                }

                NodeLinkedList.Node<Condition> node = waitingPool.push(condition);

                try {
                    condition.await(timeOut, TimeUnit.MILLISECONDS);
                } finally {
                    waitingPool.remove(node);
                }
                if(shuttingDown || Timeouts.isTimeout(timeOut)) return Optional.empty();
            } while(true);
        } finally {
            monitor.unlock();
        }
    }

    /// We only create new workers when there are tasks, therefore firstWork
    private void createNewWorker(Runnable firstWork) {
        // Logic of Worker: when created execute first work and lets go to the waiting queue and wait until we get signal
        // When signal:
        // - If spurious just update remaining
        // - If timeout or no work and to shut down lets quit
        // - If there is work get it and leave queue to finish it and then get into the queue again with updated timeout
        final Runnable worker = () -> {
            firstWork.run();
            final Condition condition = monitor.newCondition();
            Optional<Task> task;

            long limit = Timeouts.start(keepAliveTime);
            long remaining = Timeouts.remaining(limit);
            do {
                try {
                    task = getOrWait(condition, remaining);
                } catch(InterruptedException e) {
                    break;
                }

                if(task.isPresent()) {
                    task.get().command.run();
                    limit = Timeouts.start(keepAliveTime);
                } else break;

                remaining = Timeouts.remaining(limit);
            } while(true);

            currentPoolSize -= 1;
            if(shuttingDown && currentPoolSize == 0) {
                done = true;
                doneSignal.signal();
            }
        };
        currentPoolSize += 1;
        new Thread(worker).start();
    }
}
