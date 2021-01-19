import org.junit.Test;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.*;

public class SimpleThreadPoolExecuterTests {
    @Test
    public void testSingleTask() throws InterruptedException {
        /// Arrange
        final SimpleThreadPoolExecuter poolExecuter = new SimpleThreadPoolExecuter(1, 10_000);
        final int[] res = {0};
        final Runnable r1 = () -> ++res[0];

        /// Act
        final boolean b = poolExecuter.execute(r1, 100);
        Thread.sleep(100); // So Runnable can execute

        /// Assert
        assertTrue(b);
        assertEquals(1, res[0]);
    }

    @Test
    public void testMultipleTaskDone() throws InterruptedException {
        /// Arrange
        final SimpleThreadPoolExecuter poolExecuter = new SimpleThreadPoolExecuter(10, 10_000);
        final int nTasks = 50;
        final int[] res = {0};
        final Runnable r1 = () -> ++res[0];

        /// Act & Assert
        for(int i = 0; i < nTasks; i++) {
            boolean delivered = poolExecuter.execute(r1, 100);
            Thread.sleep(100); // So Runnable can execute
            assertTrue(delivered);
        }
        assertEquals(nTasks, res[0]);
    }

    @Test(expected = RejectedExecutionException.class)
    public void testRejectedTask() throws InterruptedException {
        /// Arrange
        final SimpleThreadPoolExecuter poolExecuter = new SimpleThreadPoolExecuter(1, 10_000);

        /// Act & Assert
        poolExecuter.shutdown();
        poolExecuter.execute(() -> { }, 100);
    }

    @Test
    public void testDiscardedTask() throws InterruptedException {
        /// Arrange
        final SimpleThreadPoolExecuter poolExecuter = new SimpleThreadPoolExecuter(1, 10_000);
        final Runnable r1 = () -> {
            try {
                Thread.sleep(10_000);
            } catch(InterruptedException e) {
                // Ignored for test purposes
            }
        };

        /// Act & Assert
        final boolean first = poolExecuter.execute(r1, 100);
        assertTrue(first);
        final boolean second = poolExecuter.execute(() -> { }, 5_000);
        assertFalse(second);
    }

    @Test
    public void testAwaitTerminationWithoutShutdown() throws InterruptedException {
        /// Arrange
        final SimpleThreadPoolExecuter poolExecuter = new SimpleThreadPoolExecuter(1, 10_000);

        /// Act
        final boolean done = poolExecuter.awaitTermination(2_000);

        /// Assert
        assertFalse(done);
    }
}
