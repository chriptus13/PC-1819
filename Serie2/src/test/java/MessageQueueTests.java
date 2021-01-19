import org.junit.Test;
import util.Helper;
import util.PlaceHolder;

import java.util.*;

import static org.junit.Assert.*;

public class MessageQueueTests {

    final MessageQueue<String> msgQ = new MessageQueue<>();
    final Helper helper = new Helper();

    @Test
    public void testMessageReceived() throws InterruptedException {
        /// Arrange
        final PlaceHolder<Optional<String>> res = new PlaceHolder<>();
        final PlaceHolder<Boolean> sent = new PlaceHolder<>();

        final Helper.InterruptibleRunnable r1 = () -> {
            System.out.println("Thread 1 started");
            String str = "Thread 1";
            SendStatus status = msgQ.send(str);
            try {
                sent.value = status.await(10_000);
            } catch(InterruptedException e) {
                // Ignored for test purpose
            }
            System.out.println("Thread 1 end");
        }, r2 = () -> {
            System.out.println("Thread 2 started");
            res.value = msgQ.receive(5_000);
            System.out.println("Thread 2 end");
        };

        /// Act
        helper.createAndStart(r1);
        helper.createAndStart(r2);

        helper.join();

        /// Assert
        assertTrue(sent.value);
        assertTrue(res.value.isPresent());
        assertEquals("Thread 1", res.value.get());
    }

    @Test
    public void testTwoReceivers() throws InterruptedException {
        /// Arrange
        final Set<String> set = Collections.synchronizedSet(new HashSet<>());

        final Helper.InterruptibleRunnable r1 = () -> {
            msgQ.send("Message 1");
            msgQ.send("Message 2");
        }, r2 = () -> {
            Optional<String> v = msgQ.receive(5_000);
            v.ifPresent(set::add);
        }, r3 = () -> {
            Optional<String> v = msgQ.receive(5_000);
            v.ifPresent(set::add);
        };

        /// Act
        helper.createAndStart(r1);
        helper.createAndStart(r2);
        helper.createAndStart(r3);

        helper.join();

        /// Assert
        assertTrue(set.contains("Message 1"));
        assertTrue(set.contains("Message 2"));
    }

    @Test
    public void testMessageNotDelivered() throws InterruptedException {
        /// Arrange
        final PlaceHolder<SendStatus> res = new PlaceHolder<>();

        final Helper.InterruptibleRunnable r1 = () -> res.value = msgQ.send("Message 1");

        /// Act
        helper.createAndStart(r1);
        helper.join();

        /// Assert
        assertFalse(res.value.isSent());
    }

    @Test
    public void testMessageNotReceived() throws InterruptedException {
        /// Arrange
        final PlaceHolder<Optional<String>> res = new PlaceHolder<>();

        final Helper.InterruptibleRunnable r1 = () -> res.value = msgQ.receive(5_000);

        /// Act
        helper.createAndStart(r1);
        helper.join();

        /// Assert
        assertFalse(res.value.isPresent());
    }

    @Test
    public void testMultipleMessagesSent() throws InterruptedException {
        /// Arrange
        final int nMessages = 20;
        final List<SendStatus> senders = Collections.synchronizedList(new ArrayList<>(nMessages));
        final Set<String> set = Collections.synchronizedSet(new HashSet<>());
        final Runnable r1 = () -> {
            for(int i = 0; i < nMessages; i++) {
                SendStatus sent = msgQ.send("Message" + i);
                senders.add(sent);
            }
        }, r2 = () -> {
            for(int i = 0; i < nMessages; i++) {
                Optional<String> v = msgQ.receive(100);
                v.ifPresent(set::add);
            }
        };

        /// Act & Assert
        final Thread th1 = new Thread(r1), th2 = new Thread(r2);

        th1.start();
        th1.join();

        senders.forEach(sender -> assertFalse(sender.isSent()));

        th2.start();
        th2.join();

        assertEquals(nMessages, set.size());
    }

    @Test
    public void testMultipleReceiversWaiting() throws InterruptedException {
        /// Arrange
        final int nMessages = Runtime.getRuntime().availableProcessors() - 1; // So the wait doesn't break the test
        final Set<String> set = Collections.synchronizedSet(new HashSet<>());

        /// Act & Assert
        for(int i = 0; i < nMessages; i++) {
            helper.createAndStart(() -> {
                Optional<String> v = msgQ.receive(5_000);
                v.ifPresent(set::add);
            });
        }

        for(int i = 0; i < nMessages; i++) {
            SendStatus send = msgQ.send("Message" + i);
            Thread.sleep(100);
            assertTrue(send.isSent());
        }

        helper.join();
        assertEquals(nMessages, set.size());
    }

    @Test
    public void testSendStatusWait() throws InterruptedException {
        /// Arrange
        final PlaceHolder<Optional<String>> p1 = new PlaceHolder<>();
        final Runnable r1 = () -> {
            try {
                Thread.sleep(3_000);
                p1.value = msgQ.receive(1);
            } catch(InterruptedException e) {
                // Ignored for test purposes
            }
        };

        /// Act
        final Thread th1 = new Thread(r1);
        th1.start();
        final SendStatus send = msgQ.send("Message");

        /// Assert
        assertTrue(send.await(5_000));
        assertTrue(p1.value.isPresent());
    }
}
