import org.junit.Test;
import util.Helper;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SafeMessageBoxTests {

    private final SafeMessageBox<String> msgBox = new SafeMessageBox<>();

    @Test
    public void testTryConsumeForTwoThreads() throws InterruptedException {
        /// Arrange
        final List<String> list = Collections.synchronizedList(new LinkedList<>());
        final Runnable r = () -> msgBox.tryConsume().ifPresent(list::add);
        final Thread th1 = new Thread(r), th2 = new Thread(r);

        /// Act
        msgBox.publish("Message", 2);
        th1.start();
        th2.start();
        th1.join();
        th2.join();

        /// Assert
        assertEquals(2, list.size());
    }

    @Test
    public void test1LifeMessageForTwoThreads() throws InterruptedException {
        /// Arrange
        final List<String> list = Collections.synchronizedList(new LinkedList<>());
        final Runnable r = () -> msgBox.tryConsume().ifPresent(list::add);
        final Thread th1 = new Thread(r), th2 = new Thread(r);

        /// Act
        msgBox.publish("Message", 1);
        th1.start();
        th2.start();
        th1.join();
        th2.join();

        /// Assert
        assertEquals(1, list.size());
    }

    @Test
    public void testMessageWithNoLives() {
        /// Act
        msgBox.publish("Message", 0);
        Optional<String> res = msgBox.tryConsume();

        /// Assert
        assertFalse(res.isPresent());
    }

    @Test
    public void testMessageWithMultipleLives() throws InterruptedException {
        /// Arrange
        final int nOfMessages = 10;
        final List<String> list = Collections.synchronizedList(new LinkedList<>());
        final Helper helper = new Helper();

        /// Act
        msgBox.publish("Message", nOfMessages);
        for(int i = 0; i < nOfMessages; i++) {
            helper.createAndStart(
                    () -> msgBox.tryConsume().ifPresent(list::add)
            );
        }

        helper.join();

        /// Assert
        assertEquals(nOfMessages, list.size());
    }
}
