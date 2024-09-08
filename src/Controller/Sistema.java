package Controller;

import Libs.Rngs;
import Model.MsqEvent;
import Model.MsqSum;
import Model.MsqT;
import Utils.BatchMeans;
import Utils.Distribution;
import Utils.RentalProfit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static Model.Constants.*;

public class Sistema {
    long number = 0;                     /* number of jobs in the node         */
    int e;                               /* next event index                   */
    int s;                               /* server index                       */
    long index = 0;                      /* used to count processed jobs       */
    double area = 0.0;           /* time integrated number in the node */

    double service;

    private final Rngs rngs = new Rngs();
    private final MsqT msqT = new MsqT();

    private final EventListManager eventListManager;
    private final Distribution distr;
    private final RentalProfit rentalProfit;

    private final List<MsqEvent> systemList = new ArrayList<>(NODES);
    private final List<MsqSum> sumList = new ArrayList<>(NODES + 1);

    private final List<Center> centerList = new ArrayList<>();

    public Sistema() {
        eventListManager = EventListManager.getInstance();
        distr = Distribution.getInstance();
        rentalProfit = RentalProfit.getInstance();

        eventListManager.resetState();
        rentalProfit.resetPenalty();

        Parcheggio parcheggio = new Parcheggio();
        Noleggio noleggio = new Noleggio();
        Strada strada = new Strada();
        Ricarica ricarica = new Ricarica();

        centerList.addAll(Arrays.asList(noleggio, ricarica, parcheggio, strada));

        /* 0 - noleggio, 1 - ricarica, 2 - parcheggio, 3 - strada */
        for (int i = 0; i < 4; i++) {
            systemList.add(i, new MsqEvent(0, 0));
            sumList.add(i, new MsqSum(0, 0));
        }

        // Initialize noleggio in system list
        List<MsqEvent> noleggioList = eventListManager.getServerNoleggio();
        int nextEventNoleggio = MsqEvent.getNextEvent(noleggioList, NOLEGGIO_SERVER);
        systemList.set(0, new MsqEvent(noleggioList.get(nextEventNoleggio).getT(), 1));

        // Initialize ricarica in system list
        List<MsqEvent> chargingList = eventListManager.getServerRicarica();
        int nextEventRicarica = MsqEvent.getNextEvent(chargingList, RICARICA_SERVER);
        systemList.set(1, new MsqEvent(chargingList.get(nextEventRicarica).getT(), 1));

        // Initialize parcheggio in system list
        List<MsqEvent> parcheggioList = eventListManager.getServerParcheggio();
        int nextEventParcheggio = MsqEvent.getNextEvent(parcheggioList, PARCHEGGIO_SERVER);
        systemList.set(2, new MsqEvent(parcheggioList.get(nextEventParcheggio).getT(), 1));

        // Initialize cars in noleggio
        List<MsqEvent> carInRentalStation = eventListManager.getIntQueueNoleggio();
        for (int i = 0; i < INIT_PARK_CARS; i++)
            carInRentalStation.add(i, new MsqEvent(0, 1, true));
        eventListManager.setIntQueueNoleggio(carInRentalStation);

        eventListManager.setSystemEventsList(systemList);
    }

    public void simulation(int simulationType, long seed, int runNumber) throws Exception {
        System.out.println("Starting simulation");

        switch (simulationType) {
            case 0:
                simpleSimulation(seed, runNumber);
                break;
            case 1:
                infiniteSimulation();
                break;
            default:
                throw new IllegalArgumentException("Invalid simulation choice");
        }
    }

    /* Finite horizon simulation */
    public void simpleSimulation(long seed, int runNumber) throws Exception {
        int e;
        List<MsqEvent> eventList = eventListManager.getSystemEventsList();

        while (msqT.getCurrent() < STOP_FIN) {
            if ((e = getNextEvent(eventList)) == -1) break;

//            if (e == 1 && msqT.getCurrent() > 14664) break;
//            if (msqT.getCurrent() > 86367) // break;
//                System.out.println(msqT.getCurrent() + " - " + e + " - " + rngs.getSeed());

            msqT.setNext(eventList.get(e).getT());
            this.area = this.area + (msqT.getNext() - msqT.getCurrent()) * number;
            msqT.setCurrent(msqT.getNext());

            if (e < 4) {
                centerList.get(e).simpleSimulation();
                eventList = eventListManager.getSystemEventsList();
            } else throw new Exception("Invalid event");
        }

        System.out.println("\n\n");

        for (int i = 0; i < 4; i++) centerList.get(i).printResult(runNumber, seed);

        /* Calculate profit */
        printProfit(msqT.getCurrent());
    }

    /* Infinite horizon simulation */
    public void infiniteSimulation() throws Exception {
        int e;

        msqT.setCurrent(START);
        msqT.setNext(START);

        List<MsqEvent> eventList = eventListManager.getSystemEventsList();

        while (pendingEvents()) {
            if ((e = getNextEvent(eventList)) == -1) break;

            msqT.setNext(eventList.get(e).getT());
            this.area = this.area + (msqT.getNext() - msqT.getCurrent()) * number;
            msqT.setCurrent(msqT.getNext());

            if (e < 4) {
                centerList.get(e).infiniteSimulation();
                eventList = eventListManager.getSystemEventsList();
            } else throw new Exception("Invalid event");
        }

        for (int i = 0; i < 4; i++) centerList.get(i).printResult(-1, -1L);

        /* Calculate profit */
        printProfit(msqT.getCurrent());
    }

    private void printProfit(double lastEventTime) {
        System.out.println("Analisi dei profitti:\n\n");
        System.out.println("  Profit .. = " + rentalProfit.getProfit());
        System.out.println("  Cost .... = " + rentalProfit.getCost(lastEventTime));
    }

    private void printResult(int runNumber) {
        System.out.println("Sistema\n\n");
        System.out.println("for " + index + " jobs the service node statistics are:\n\n");
        System.out.println("  avg interarrivals .. = " + eventListManager.getSystemEventsList().getFirst().getT() / index);
        System.out.println("  avg wait ........... = " + area / index);
        System.out.println("  avg # in node ...... = " + area / msqT.getCurrent());

        for (int i = 1; i <= NODES; i++) {
            area -= sumList.get(i).getService();
        }
        System.out.println("  avg delay .......... = " + area / index);
        System.out.println("  avg # in queue ..... = " + area / msqT.getCurrent());
        System.out.println("\nthe server statistics are:\n\n");
        System.out.println("    server     utilization     avg service        share\n");
        for (int i = 1; i <= NODES; i++) {
            System.out.println(i + "\t" + sumList.get(i).getService() / msqT.getCurrent() + "\t" + sumList.get(i).getService() / sumList.get(i).getServed() + "\t" + ((double) sumList.get(i).getServed() / index));
        }
        System.out.println("\n");
    }

    /* Fetch index of most imminent event among all servers */
    private int getNextEvent(List<MsqEvent> eventList) {
        double threshold = Double.MAX_VALUE;
        int e = -1;
        int i = 0;

        for (MsqEvent event : eventList) {
            if (event.getT() < threshold && event.getX() == 1) {
                threshold = event.getT();
                e = i;
            }
            i++;
        }
        return e;
    }

    /* Check if there is a centre that has not processed B*K events */
    private boolean pendingEvents() {
        return BatchMeans.getJobInBatch() <= B * K;
    }
}
