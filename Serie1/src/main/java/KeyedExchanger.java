import util.Timeouts;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class KeyedExchanger<T> {
    private final Lock monitor = new ReentrantLock();
    private final Map<Integer, PlaceHolder> map = new HashMap<>();

    private class PlaceHolder {
        final Condition condition;
        T value;

        PlaceHolder(T value, Condition condition) {
            this.value = value;
            this.condition = condition;
        }

        T switchAndGet(T other) {
            T temp = value;
            value = other;
            return temp;
        }
    }

    public Optional<T> exchange(int key, T myData, int timeoutMs) throws InterruptedException {
        try {
            monitor.lock();

            PlaceHolder val = map.get(key);

            // Happy path
            if(val != null) {
                // Remove so other Threads don't access this value between condition signal and mutual exclusion acquire by first exchanger
                map.remove(key);
                T t = val.switchAndGet(myData);
                val.condition.signal();
                return Optional.of(t);
            }

            // Check if can wait
            if(Timeouts.noWait(timeoutMs)) return Optional.empty();

            // Insert value into map
            val = new PlaceHolder(myData, monitor.newCondition());
            map.put(key, val);

            // Calculate times
            long limit = Timeouts.start(timeoutMs);
            long remaining = Timeouts.remaining(limit);
            // Stay waiting while no Timeout or no Trade
            for(; !Timeouts.isTimeout(remaining); remaining = Timeouts.remaining(limit)) {
                try {
                    val.condition.await(remaining, TimeUnit.MILLISECONDS);
                } catch(InterruptedException e) {
                    if(val.value != myData) {
                        Thread.currentThread().interrupt();
                        return Optional.of(val.value);
                    }
                    map.remove(key);
                    throw e;
                }
                // If value references are different the other Thread already switched
                if(val.value != myData) return Optional.of(val.value);
            }
            map.remove(key);
            return Optional.empty();
        } finally {
            monitor.unlock();
        }
    }

    public Optional<T> exchange(int key, T myData) throws InterruptedException {
        return exchange(key, myData, Integer.MAX_VALUE);
    }

    public Optional<T> exchangeNow(int key, T myData) throws InterruptedException {
        return exchange(key, myData, 0);
    }
}
