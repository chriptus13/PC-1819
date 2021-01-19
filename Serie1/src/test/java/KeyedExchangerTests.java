import org.junit.Test;
import util.Helper;
import util.PlaceHolder;

import java.util.Optional;

import static org.junit.Assert.*;

public class KeyedExchangerTests {
    private final Helper helper = new Helper();
    private final KeyedExchanger<String> trader = new KeyedExchanger<>();

    @Test
    public void testExchangeTrade() throws InterruptedException {
        /// Arrange
        final int id = 10;
        final PlaceHolder<Optional<String>> resA = new PlaceHolder<>(),
                resB = new PlaceHolder<>();
        final Helper.InterruptibleRunnable r1 = () -> {
            try {
                String str = "Thread 1";
                resA.value = trader.exchange(id, str, 5_000);
            } catch(InterruptedException e) {
                // ignored for test purposes
            }
        }, r2 = () -> {
            try {
                String str = "Thread 2";
                resB.value = trader.exchange(id, str, 5_000);
            } catch(InterruptedException e) {
                // ignored for current test purpose
            }
        };

        /// Act
        helper.createAndStart(r1);
        helper.createAndStart(r2);

        helper.join();

        /// Assert
        // Thread 1 should've received the String from Thread 2
        assertTrue(resA.value.isPresent());
        assertEquals("Thread 2", resA.value.get());
        // Thread 2 should've received the String from Thread 1
        assertTrue(resB.value.isPresent());
        assertEquals("Thread 1", resB.value.get());
    }

    @Test
    public void testExchangeNoTimeout() throws InterruptedException {
        /// Arrange
        final int id = 10;
        final PlaceHolder<Optional<String>> res = new PlaceHolder<>();
        final Helper.InterruptibleRunnable r = () -> {
            try {
                String str = "Thread 1";
                res.value = trader.exchange(id, str, 0);
            } catch(InterruptedException e) {
                // ignored for current test purpose
            }
        };

        /// Act
        helper.createAndStart(r);

        helper.join();

        /// Assert
        // With no time to wait and no value given by other thread
        // exchange should return an empty Optional
        assertFalse(res.value.isPresent());
    }

    @Test
    public void testExchangeTimeout() throws InterruptedException {
        /// Arrange
        final int id = 10;
        final PlaceHolder<Optional<String>> res = new PlaceHolder<>();
        final Helper.InterruptibleRunnable r1 = () -> {
            try {
                String str = "Thread 1";
                res.value = trader.exchange(id, str, 5_000);
            } catch(InterruptedException e) {
                // ignored for current test purpose
            }
        }, r2 = () -> {
            try {
                String str = "Thread 2";
                Thread.sleep(6_000);
                trader.exchange(id, str, 0);
            } catch(InterruptedException e) {
                // ignored for current test purpose
            }
        };

        /// Act
        helper.createAndStart(r1);
        helper.createAndStart(r2);

        helper.join();

        /// Assert
        // Exchange on Thread 1 should return after 5 seconds with
        // an empty Optional because Thread 2 didn't respond to trade
        // before the timeout
        assertFalse(res.value.isPresent());
    }

    @Test
    public void testExchangeInterruption() throws InterruptedException {
        /// Arrange
        final int id = 10;
        final PlaceHolder<Optional<String>> res = new PlaceHolder<>();
        final Runnable r1 = () -> {
            try {
                String str = "Thread 1";
                res.value = trader.exchange(id, str, 5_000);
            } catch(InterruptedException e) {
                // ignored for test purposes
            }
        }, r2 = () -> {
            try {
                String str = "Thread 2";
                Thread.sleep(2_000);
                trader.exchange(id, str, 5_000);
            } catch(InterruptedException e) {
                // ignored for current test purpose
            }
        };

        /// Act
        Thread th1 = new Thread(r1), th2 = new Thread(r2);
        th1.start();
        th2.start();
        th1.interrupt();
        th1.join();
        th2.join();

        /// Assert
        // If Thread 1 is interrupted means exchange didn't return a value
        // therefore the result should be null
        assertNull(res.value);
    }

    @Test
    public void testExchangeMultipleTrades() throws InterruptedException {
        /// Arrange
        final int idA = 10, idB = 20;
        final PlaceHolder<Optional<String>> resA = new PlaceHolder<>(), resB = new PlaceHolder<>();
        final Helper.InterruptibleRunnable r1 = () -> {
            try {
                String str = "Thread 1A";
                resA.value = trader.exchange(idA, str, 5_000);
            } catch(InterruptedException e) {
                // ignored for test purposes
            }
        }, r2 = () -> {
            try {
                String str = "Thread 2A";
                trader.exchange(idA, str, 5_000);
            } catch(InterruptedException e) {
                // ignored for current test purpose
            }
        }, r3 = () -> {
            try {
                String str = "Thread 1B";
                resB.value = trader.exchange(idB, str, 5_000);
            } catch(InterruptedException e) {
                // ignored for current test purpose
            }
        }, r4 = () -> {
            try {
                String str = "Thread 2B";
                trader.exchange(idB, str, 5_000);
            } catch(InterruptedException e) {
                // ignored for current test purpose
            }
        };

        /// Act
        helper.createAndStart(r1);
        helper.createAndStart(r2);
        helper.createAndStart(r3);
        helper.createAndStart(r4);

        helper.join();

        /// Assert
        // Thread 1 should've received the String from Thread 2
        assertTrue(resA.value.isPresent());
        assertEquals("Thread 2A", resA.value.get());
        // Thread 3 should've received the String from Thread 4
        assertTrue(resB.value.isPresent());
        assertEquals("Thread 2B", resB.value.get());
    }

    @Test
    public void testExchangeEntryRemovalAfterTrade() throws InterruptedException {
        /// Arrange
        final int idA = 10;
        final PlaceHolder<Optional<String>> resA = new PlaceHolder<>(), resB = new PlaceHolder<>();
        final Helper.InterruptibleRunnable r1 = () -> {
            try {
                String str = "Thread A";
                resA.value = trader.exchange(idA, str, 2_000);
            } catch(InterruptedException e) {
                // ignored for test purposes
            }
        }, r2 = () -> {
            try {
                String str = "Thread B";
                trader.exchange(idA, str, 2_000);
            } catch(InterruptedException e) {
                // ignored for current test purpose
            }
        }, r3 = () -> {
            try {
                String str = "Thread C";
                Thread.sleep(5_000);
                resB.value = trader.exchange(idA, str, 0);
            } catch(InterruptedException e) {
                // ignored for current test purpose
            }
        };

        /// Act
        helper.createAndStart(r1);
        helper.createAndStart(r2);
        helper.createAndStart(r3);

        helper.join();

        /// Assert
        // Thread 1 and Thread 2 should trade successfully
        assertTrue(resA.value.isPresent());
        assertEquals("Thread B", resA.value.get());
        // Thread 3 doesn't get anything with the same id because it was already removed
        assertFalse(resB.value.isPresent());
    }
}
