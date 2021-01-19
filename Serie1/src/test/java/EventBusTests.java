import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EventBusTests {

    @Test
    public void testSingleEvent() throws InterruptedException {
        /// Arrange
        final EventBus eventBus = new EventBus(10);
        final int nEvents = 5;
        final int[] i = {0};

        final Runnable r1 = () -> {
            try {
                eventBus.subscribeEvent(str -> ++i[0], String.class);
            } catch(InterruptedException e) {
                // Ignored for test purposes
            }
        };

        /// Act
        final Thread th1 = new Thread(r1);
        th1.start();

        Thread.sleep(100);    // Wait for Th1 subscribe
        for(int j = 0; j < nEvents; j++) eventBus.publishEvent("Event");
        eventBus.shutdown();
        th1.join();

        /// Assert
        assertEquals(nEvents, i[0]);
    }

    @Test
    public void testDifferentTypesEventBus() throws InterruptedException {
        /// Arrange
        final EventBus eventBus = new EventBus(10);
        final int nEvents = 5;
        final int[] i = {0};

        final Runnable r1 = () -> {
            try {
                eventBus.subscribeEvent(str -> ++i[0], String.class);
            } catch(InterruptedException e) {
                // Ignored for test purposes
            }
        }, r2 = () -> {
            try {
                eventBus.subscribeEvent(it -> ++i[0], Integer.class);
            } catch(InterruptedException e) {
                // Ignored for test purposes
            }
        };

        /// Act
        final Thread th1 = new Thread(r1), th2 = new Thread(r2);
        th1.start();
        th2.start();

        Thread.sleep(100); // Allow th1 and th2 to subscribe
        for(int j = 0; j < nEvents; j++) {
            eventBus.publishEvent("Event");
            eventBus.publishEvent(1);
        }
        eventBus.shutdown();
        th1.join();
        th2.join();

        /// Assert
        assertEquals(nEvents * 2, i[0]);
    }

    @Test
    public void testDiscardedEvents() throws InterruptedException {
        /// Arrange
        final int maxPending = 5;
        final EventBus eventBus = new EventBus(maxPending);
        final int nEvents = 10;
        final int[] i = {0};

        final Runnable r1 = () -> {
            try {
                eventBus.subscribeEvent(str -> ++i[0], String.class);
            } catch(InterruptedException e) {
                // Ignored for test purposes
            }
        };

        /// Act
        final Thread th1 = new Thread(r1);
        th1.start();

        Thread.sleep(100); // Allow th1 to subscribe
        for(int j = 0; j < nEvents; j++) eventBus.publishEvent("Event");
        eventBus.shutdown();
        th1.join();

        /// Assert
        assertEquals(maxPending, i[0]);
    }

    @Test
    public void testEventsWithoutHandlers() throws InterruptedException {
        /// Arrange
        final EventBus eventBus = new EventBus(10);
        final int[] i = {0};
        final int nEvents = 5;

        final Runnable r1 = () -> {
            try {
                eventBus.subscribeEvent(str -> ++i[0], String.class);
            } catch(InterruptedException e) {
                // Ignored for test purposes
            }
        };

        /// Act
        final Thread th1 = new Thread(r1);
        th1.start();

        Thread.sleep(100);
        for(int j = 0; j < nEvents; j++) {
            eventBus.publishEvent("Event");
            eventBus.publishEvent(1);
        }
        eventBus.shutdown();
        th1.join();

        /// Assert
        assertEquals(nEvents, i[0]);
    }

    @Test
    public void testInterruptedHandler() throws InterruptedException {
        /// Arrange
        final EventBus eventBus = new EventBus(10);
        final int[] i = {0};
        final int nEvents = 6;

        final Runnable r1 = () -> {
            try {
                eventBus.subscribeEvent(str -> ++i[0], String.class);
            } catch(InterruptedException e) {
                System.out.println("Interrupted");
                // Ignored for test purposes
            }
        };

        /// Act
        final Thread th1 = new Thread(r1);
        th1.start();

        Thread.sleep(100);
        for(int j = 0; j < nEvents; j++) {
            if(j == 3) {
                th1.interrupt();
                Thread.sleep(100);
            }
            eventBus.publishEvent("Event");
        }
        eventBus.shutdown();

        /// Assert
        assertEquals(3, i[0]);
    }
}
