package edu.technopolis.advancedjava;

public class Deadlock {
    private static final Object FIRST_LOCK = new Object();
    private static final Object SECOND_LOCK = new Object();
    private static final ConcurrentBarrier barrier = new ConcurrentBarrier(2);

    public static void main(String[] args) throws Exception {
        Thread ft = new Thread(Deadlock::first);
        Thread st = new Thread(Deadlock::second);
        ft.start();
        st.start();
        ft.join();
        st.join();
        //never going to reach this point
    }

    private static void first() {
        synchronized(FIRST_LOCK) {
            barrier.passBarrier();
            synchronized (SECOND_LOCK) {

            }
        }
    }

    private static void second() {
    
        //reverse order of monitors
        synchronized(SECOND_LOCK) {
            barrier.passBarrier();
            synchronized(FIRST_LOCK) {
                //unreachable point
            }
        }

    }


}