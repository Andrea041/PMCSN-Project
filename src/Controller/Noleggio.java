package Controller;

import Model.MsqEvent;
import Model.MsqSum;
import Model.MsqT;
import Util.Distribution;

import java.util.ArrayList;
import java.util.List;

import static Model.Constants.*;

public class Noleggio implements Center {
    private final EventListManager eventListManager;

    long number = 0;                /* number in the node                 */
    int e;                          /* next event index                   */
    int s;                          /* server index                       */
    long index = 0;                 /* used to count processed jobs       */
    double service;
    double area = 0.0;      /* time integrated number in the node */

    private final List<MsqEvent> eventList = new ArrayList<>(NOLEGGIO_SERVER + 1);
    private final List<MsqSum> sumList = new ArrayList<>(NOLEGGIO_SERVER + 1);
    private final MsqT msqT = new MsqT();

    private final Distribution distr = Distribution.getInstance();

    public Noleggio() {
        this.eventListManager = EventListManager.getInstance();

        for (s = 0; s < NOLEGGIO_SERVER + 1; s++) {
            this.eventList.add(s, new MsqEvent(0, 0));
            this.sumList.add(s, new MsqSum());
        }

        // First arrival event (Passenger)
        double arrival = distr.getArrival(0);

        // Add this new event and setting time to arrival time
        this.eventList.set(0, new MsqEvent(arrival, 1));

        // Setting event list in eventListManager
        this.eventListManager.setServerNoleggio(eventList);
    }

    @Override
    public void simpleSimulation() {
        MsqEvent event;

        List<MsqEvent> eventList = eventListManager.getServerNoleggio();
        List<MsqEvent> internalEventList = eventListManager.getIntQueueNoleggio();

        /* No event to process */
        if (eventList.getFirst().getX() == 0 && internalEventList.isEmpty() && this.number == 0) return;

        int e = MsqEvent.getNextEvent(eventList, NOLEGGIO_SERVER);

        msqT.setNext(eventList.get(e).getT());
        area += (msqT.getNext() - msqT.getCurrent()) * number;
        msqT.setCurrent(msqT.getNext());

        // External arrival (λ) and a car is ready to be rented
        if (e == 0 && !internalEventList.isEmpty()) {
            this.number++;

            eventList.getFirst().setT(distr.getArrival(0)); /* Get new arrival from passenger arrival */

            if (eventList.getFirst().getT() > STOP_FIN) {
                eventList.getFirst().setX(0);
                eventListManager.setServerNoleggio(eventList); // TODO superfluo? Fatto alla fine
            }

            if (number <= NOLEGGIO_SERVER) {
                service = distr.getService(0);
                s = MsqEvent.findOne(eventList, NOLEGGIO_SERVER);

                /* Set server as active */
                eventList.get(s).setT(msqT.getCurrent() +  service);
                eventList.get(s).setX(1);
                internalEventList.removeFirst();

                sumList.get(s).incrementService(service);
                sumList.get(s).incrementServed();
            }
        } else if (e != 0) {    /* Process a departure */
            this.index++;
            this.number--;

            /* Update number of available cars in the center depending on where the car comes from */
            if (internalEventList.getFirst().isFromParking()) {
                if (eventListManager.reduceCarsInParcheggio() != 0) {
                    throw new RuntimeException("ReduceCarsInParcheggio error");
                }
            } else {
                if (eventListManager.reduceCarsInRicarica() != 0) {
                    throw new RuntimeException("ReduceCarsInRicarica error");
                }
            }

            s = e;

            /* Virtual move of job from Noleggio to Strada */
            event = eventList.get(s);
            event.setX(1);

            List<MsqEvent> serverStrada = eventListManager.getServerStrada();
            serverStrada.set(0, event);
            eventListManager.setServerStrada(serverStrada);

            if (number >= NOLEGGIO_SERVER) {        /* there is some jobs in queue, place another job in this server */
                service = distr.getService(0);
                eventList.get(s).setT(msqT.getCurrent() + service);

                sumList.get(s).incrementService(service);
                sumList.get(s).incrementServed();
            } else                                    /* no job in queue, simply remove it from server */
                eventList.get(s).setX(0);
        }

        eventListManager.setServerNoleggio(eventList);
        eventListManager.setIntQueueNoleggio(internalEventList);
    }

    @Override
    public void printResult() {
        System.out.println("Noleggio\n\n");
        System.out.println("for " + index + " jobs the service node statistics are:\n\n");
        System.out.println("  avg interarrivals .. = " + eventListManager.getSystemEventsList().getFirst().getT() / index);
        System.out.println("  avg wait ........... = " + area / index);
        System.out.println("  avg # in node ...... = " + area / msqT.getCurrent());

        for(int i = 1; i == NOLEGGIO_SERVER; i++) {
            area -= sumList.get(i).getService();
        }
        System.out.println("  avg delay .......... = " + area / index);
        System.out.println("  avg # in queue ..... = " + area / msqT.getCurrent());
        System.out.println("\nthe server statistics are:\n\n");
        System.out.println("    server     utilization     avg service        share\n");
        for(int i = 1; i == NOLEGGIO_SERVER; i++) {
            System.out.println(i + "\t" + sumList.get(i).getService() / msqT.getCurrent() + "\t" + sumList.get(i).getService() / sumList.get(i).getServed() + "\t" + ((double)sumList.get(i).getServed() / index));
        }
        System.out.println("\n");
    }
}
