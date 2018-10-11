/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package tournaments;

import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ContinuingAI;
import ai.core.InterruptibleAI;
import gui.PhysicalGameStateJFrame;
import gui.PhysicalGameStatePanel;

import java.io.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import rts.GameState;
import rts.PartiallyObservableGameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.Trace;
import rts.TraceEntry;
import rts.units.Unit;
import rts.units.UnitTypeTable;
import util.XMLWriter;

/**
 *
 * @author santi
 */
public class RoundRobinTournament {

    @SuppressWarnings("FieldCanBeLocal")
    private static boolean visualize = false;
    @SuppressWarnings("FieldCanBeLocal")
    private static int TIMEOUT_CHECK_TOLERANCE = 20;
    @SuppressWarnings("FieldCanBeLocal")
    private static boolean USE_CONTINUING_ON_INTERRUPTIBLE = false;

    /**
     * Method to run round robin tournaments.
     */
    public static void runTournament(List<AI> AIs,
                                     int playOnlyGamesInvolvingThisAI,
                                     List<String> maps,
                                     int rounds,
                                     int maxGameLength,
                                     int timeBudget,
                                     int iterationsBudget,
                                     long preAnalysisBudgetFirstTimeInAMap,
                                     long preAnalysisBudgetRestOfTimes,
                                     boolean fullObservability,
                                     boolean selfMatches,
                                     boolean timeoutCheck,
                                     boolean runGC,
                                     boolean preAnalysis,
                                     UnitTypeTable utt,
                                     String traceOutputfolder,
                                     Writer out,
                                     Writer progress,
                                     String folderForReadWriteFolders) throws Exception {

        if (progress != null) {
            progress.write("\nRoundRobinTournament: Starting tournament\n");
            progress.flush();
        }

        // Variables to keep track of tournament stats
        int wins[][] = new int[AIs.size()][AIs.size()];
        int ties[][] = new int[AIs.size()][AIs.size()];
        int AIcrashes[][] = new int[AIs.size()][AIs.size()];
        int AItimeout[][] = new int[AIs.size()][AIs.size()];
        double accumTime[][] = new double[AIs.size()][AIs.size()];

        // Write tournament information
        out.write("RoundRobinTournament\n");
        out.write("AIs:\n");
        for (ai.core.AI AI : AIs) {
            out.write("\t" + AI.toString() + "\n");
        }
        out.write("Maps:\n");
        for (String map : maps) {
            out.write("\t" + map + "\n");
        }

        // Write tournament settings
        out.write("Settings:\n");
        out.write("\tRounds:\t" + rounds + "\n");
        out.write("\tMax Game Length:\t" + maxGameLength + "\n");
        out.write("\tTime Budget:\t" + timeBudget + "\n");
        out.write("\tIterations Budget:\t" + iterationsBudget + "\n");
        out.write("\tPregame Analysis Budget:\t" + preAnalysisBudgetFirstTimeInAMap + "\t" + preAnalysisBudgetRestOfTimes + "\n");
        out.write("\tFull Observability:\t" + fullObservability + "\n");
        out.write("\tTimeout Check:\t" + timeoutCheck + "\n");
        out.write("\tRun Garbage Collector:\t" + runGC + "\n");

        out.flush();

        // Create all the read/write folders:
        String readWriteFolders[] = new String[AIs.size()];
        boolean firstPreAnalysis[][] = new boolean[AIs.size()][maps.size()];
        createAIReadWriteFolders(AIs, maps, folderForReadWriteFolders, readWriteFolders, firstPreAnalysis);

        // Run tournament
        for (int round = 0; round < rounds; round++) {  // For each round
            for (int map_idx = 0; map_idx < maps.size(); map_idx++) {  // For each map

                // Load map
                PhysicalGameState pgs = PhysicalGameState.load(maps.get(map_idx), utt);

                // Pair up the AIs
                for (int ai1_idx = 0; ai1_idx < AIs.size(); ai1_idx++) {
                    for (int ai2_idx = 0; ai2_idx < AIs.size(); ai2_idx++) {

                        // Check for self-play matches
                        if (!selfMatches && ai1_idx == ai2_idx) continue;

                        // Check for AI if one required to be involved in game
                        if (playOnlyGamesInvolvingThisAI!=-1) {
                            if (ai1_idx != playOnlyGamesInvolvingThisAI &&
                                    ai2_idx != playOnlyGamesInvolvingThisAI) continue;
                        }

                        // Variables to keep track of time usage amongst the AIs:
                        int numTimes1 = 0;
                        int numTimes2 = 0;
                        double averageTime1 = 0;
                        double averageTime2 = 0;
                        int numberOfTimeOverBudget1 = 0;
                        int numberOfTimeOverBudget2 = 0;
                        double averageTimeOverBudget1 = 0;
                        double averageTimeOverBudget2 = 0;
                        int numberOfTimeOverTwiceBudget1 = 0;
                        int numberOfTimeOverTwiceBudget2 = 0;
                        double averageTimeOverTwiceBudget1 = 0;
                        double averageTimeOverTwiceBudget2 = 0;

                        // Cloning AIs to keep them separate between games
                        AI ai1 = AIs.get(ai1_idx).clone();
                        AI ai2 = AIs.get(ai2_idx).clone();

                        // Give time and iteration budgets to AIWithComputationBudget types
                        if (ai1 instanceof AIWithComputationBudget) {
                            ((AIWithComputationBudget) ai1).setTimeBudget(timeBudget);
                            ((AIWithComputationBudget) ai1).setIterationsBudget(iterationsBudget);
                        }
                        if (ai2 instanceof AIWithComputationBudget) {
                            ((AIWithComputationBudget) ai2).setTimeBudget(timeBudget);
                            ((AIWithComputationBudget) ai2).setIterationsBudget(iterationsBudget);
                        }

                        // Create continuing AI if required
                        if (USE_CONTINUING_ON_INTERRUPTIBLE) {
                            if (ai1 instanceof InterruptibleAI) ai1 = new ContinuingAI(ai1);
                            if (ai2 instanceof InterruptibleAI) ai2 = new ContinuingAI(ai2);
                        }

                        // Reset AIs
                        ai1.reset();
                        ai2.reset();

                        // Initialize game state and frame if visualising
                        GameState gs = new GameState(pgs.clone(), utt);
                        PhysicalGameStateJFrame w = null;
                        if (visualize) w = PhysicalGameStatePanel.newVisualizer(gs, 600, 600, !fullObservability);

                        // Write up progress
                        if (progress != null) {
                            progress.write("\nMATCH UP: " + ai1 + " vs " + ai2 + "\n");
                        }

                        // Do pre game analysis
                        preAnalysis(preAnalysisBudgetFirstTimeInAMap, preAnalysisBudgetRestOfTimes, preAnalysis,
                                progress, readWriteFolders, firstPreAnalysis, map_idx, ai1_idx, ai2_idx, ai1, ai2, gs);

                        // Initialize for actual game
                        boolean gameover = false;
                        int crashed = -1;
                        int timedout = -1;

                        // Keep track of action traces
                        Trace trace = null;
                        TraceEntry te;
                        if (traceOutputfolder != null) {
                            trace = new Trace(utt);
                            te = new TraceEntry(gs.getPhysicalGameState().clone(), gs.getTime());
                            trace.addEntry(te);
                        }

                        // Play game
                        do {
                            PlayerAction pa1 = null;
                            PlayerAction pa2 = null;
                            long AI1start = 0, AI2start = 0, AI1end = 0, AI2end = 0;

                            // Full Observability
                            if (fullObservability) {
                                if (runGC) {  // Run Garbage Collector
                                    System.gc();
                                }
                                GameState clone = gs.clone();

                                // Get ai1 action
                                try {
                                    AI1start = System.currentTimeMillis();
                                    pa1 = ai1.getAction(0, gs);
                                    AI1end = System.currentTimeMillis();
                                    checkCheating(gs, clone, 0);
                                } catch (Exception e) {
                                    crashed = 0;
                                    break;
                                }
                                if (runGC) {  // Run Garbage Collector
                                    System.gc();
                                }

                                // Get ai2 action
                                try {
                                    AI2start = System.currentTimeMillis();
                                    pa2 = ai2.getAction(1, gs);
                                    AI2end = System.currentTimeMillis();
                                    checkCheating(gs, clone, 1);
                                } catch (Exception e) {
                                    crashed = 1;
                                    break;
                                }

                                // Partial Observability
                            } else {
                                if (runGC) {  // Run Garbage Collector
                                    System.gc();
                                }

                                // Get ai1 action
                                try {
                                    PartiallyObservableGameState po_gs = new PartiallyObservableGameState(gs, 0);
                                    PartiallyObservableGameState clone = po_gs.clone();
                                    AI1start = System.currentTimeMillis();
                                    pa1 = ai1.getAction(0, po_gs);
                                    AI1end = System.currentTimeMillis();
                                    checkCheating(po_gs, clone, 0);
                                } catch (Exception e) {
                                    crashed = 0;
                                    break;
                                }
                                if (runGC) {
                                    System.gc();
                                }

                                // Get ai1 action
                                try {
                                    PartiallyObservableGameState po_gs = new PartiallyObservableGameState(gs, 1);
                                    PartiallyObservableGameState clone = po_gs.clone();
                                    AI2start = System.currentTimeMillis();
                                    pa2 = ai2.getAction(1, po_gs);
                                    AI2end = System.currentTimeMillis();
                                    checkCheating(po_gs, clone, 1);
                                } catch (Exception e) {
                                    crashed = 1;
                                    break;
                                }
                            }

                            // Check if AIs are respecting the time budget
                            long AI1time = AI1end - AI1start;
                            long AI2time = AI2end - AI2start;
                            numTimes1++;
                            numTimes2++;
                            averageTime1+=AI1time;
                            averageTime2+=AI2time;

                            // Check AI1
                            if (AI1time > timeBudget) {  // Generally over budget
                                numberOfTimeOverBudget1++;
                                averageTimeOverBudget1 += AI1time;
                                if (AI1time > timeBudget*2) {  // Over twice the budget!
                                    numberOfTimeOverTwiceBudget1++;
                                    averageTimeOverTwiceBudget1 += AI1time;
                                }
                            }

                            // Check AI2
                            if (AI2time > timeBudget) {  // Generally over budget
                                numberOfTimeOverBudget2++;
                                averageTimeOverBudget2 += AI2time;
                                if (AI2time > timeBudget*2) {  // Over twice the budget!
                                    numberOfTimeOverTwiceBudget2++;
                                    averageTimeOverTwiceBudget2 += AI2time;
                                }
                            }

                            // Timeout agents and end the game
                            if (timeoutCheck) {
                                if (AI1time > timeBudget + TIMEOUT_CHECK_TOLERANCE) {
                                    timedout = 0;
                                    break;
                                }
                                if (AI2time > timeBudget + TIMEOUT_CHECK_TOLERANCE) {
                                    timedout = 1;
                                    break;
                                }
                            }

                            // Add actions to action traces
                            addTraces(traceOutputfolder, gs, trace, pa1, pa2);

                            // Game tick forward
                            gs.issueSafe(pa1);
                            gs.issueSafe(pa2);
                            gameover = gs.cycle();

                            windowSleep(gs, w);

                            // Keep playing while game is not over and not reached maximum length
                        } while (!gameover && (gs.getTime() < maxGameLength));

                        // Dispose of window if used
                        if (w!=null) w.dispose();

                        // Zip action traces in correct files
                        zipTraces(traceOutputfolder, round, map_idx, ai1_idx, ai2_idx, gs, trace);

                        // Find the winner of the game, crahses and timeouts
                        int winner;
                        if (crashed != -1) {
                            winner = 1 - crashed;
                            if (crashed == 0) {
                                AIcrashes[ai1_idx][ai2_idx]++;
                            }
                            if (crashed == 1) {
                                AIcrashes[ai2_idx][ai1_idx]++;
                            }
                        } else if (timedout != -1) {
                            winner = 1 - timedout;
                            if (timedout == 0) {
                                AItimeout[ai1_idx][ai2_idx]++;
                            }
                            if (timedout == 1) {
                                AItimeout[ai2_idx][ai1_idx]++;
                            }
                        } else {
                            winner = gs.winner();
                        }

                        // Tell the agents who won the game
                        ai1.gameOver(winner);
                        ai2.gameOver(winner);

                        // Output result
                        out.write("\niteration\tmap\tai1\tai2\ttime\twinner\tcrashed\ttimedout\n");
                        out.write(round + "\t" + map_idx + "\t" + ai1_idx + "\t" + ai2_idx + "\t"
                                + gs.getTime() + "\t" + winner + "\t" + crashed + "\t" + timedout + "\n");
                        out.flush();

                        // Write progress
                        if (progress != null) {
                            progress.write("Winner: " + winner + "  in " + gs.getTime() + " cycles\n");
                            progress.write(ai1 + " : " + ai1.statisticsString() + "\n");
                            progress.write(ai2 + " : " + ai2.statisticsString() + "\n");
                            progress.write("AI1 time usage, average:  " + (averageTime1/numTimes1) +
                                    ", # times over budget: " + numberOfTimeOverBudget1 + " (avg " +
                                    (averageTimeOverBudget1/numberOfTimeOverBudget1) +
                                    ") , # times over 2*budget: " + numberOfTimeOverTwiceBudget1 + " (avg " +
                                    (averageTimeOverTwiceBudget1/numberOfTimeOverTwiceBudget1) + ")\n");
                            progress.write("AI2 time usage, average:  " + (averageTime2/numTimes2) +
                                    ", # times over budget: " + numberOfTimeOverBudget2 + " (avg " +
                                    (averageTimeOverBudget2/numberOfTimeOverBudget2) +
                                    ") , # times over 2*budget: " + numberOfTimeOverTwiceBudget2 + " (avg " +
                                    (averageTimeOverTwiceBudget2/numberOfTimeOverTwiceBudget2) + ")\n");
                            progress.flush();
                        }

                        // Keep track of overall tournament stats
                        if (winner == -1) {
                            ties[ai1_idx][ai2_idx]++;
                            ties[ai2_idx][ai1_idx]++;
                        } else if (winner == 0) {
                            wins[ai1_idx][ai2_idx]++;
                        } else if (winner == 1) {
                            wins[ai2_idx][ai1_idx]++;
                        }
                        accumTime[ai1_idx][ai2_idx] += gs.getTime();
                    }
                }
            }
        }

        // Output overall tournament stats

        out.write("\nWins:\n");
        for (int ai1_idx = 0; ai1_idx < AIs.size(); ai1_idx++) {
            for (int ai2_idx = 0; ai2_idx < AIs.size(); ai2_idx++) {
                out.write(wins[ai1_idx][ai2_idx] + "\t");
            }
            out.write("\n");
        }
        out.write("\nTies:\n");
        for (int ai1_idx = 0; ai1_idx < AIs.size(); ai1_idx++) {
            for (int ai2_idx = 0; ai2_idx < AIs.size(); ai2_idx++) {
                out.write(ties[ai1_idx][ai2_idx] + "\t");
            }
            out.write("\n");
        }
        out.write("\nAverage Game Length:\n");
        for (int ai1_idx = 0; ai1_idx < AIs.size(); ai1_idx++) {
            for (int ai2_idx = 0; ai2_idx < AIs.size(); ai2_idx++) {
                out.write(accumTime[ai1_idx][ai2_idx] / (maps.size() * rounds) + "\t");
            }
            out.write("\n");
        }
        out.write("\nAI crashes:\n");
        for (int ai1_idx = 0; ai1_idx < AIs.size(); ai1_idx++) {
            for (int ai2_idx = 0; ai2_idx < AIs.size(); ai2_idx++) {
                out.write(AIcrashes[ai1_idx][ai2_idx] + "\t");
            }
            out.write("\n");
        }
        out.write("\nAI timeout:\n");
        for (int ai1_idx = 0; ai1_idx < AIs.size(); ai1_idx++) {
            for (int ai2_idx = 0; ai2_idx < AIs.size(); ai2_idx++) {
                out.write(AItimeout[ai1_idx][ai2_idx] + "\t");
            }
            out.write("\n");
        }
        out.flush();

        // Announce end of tournament
        if (progress != null) {
            progress.write("\nRoundRobinTournament: tournament ended\n");
            progress.flush();
        }
    }

    /**
     * Method to zip action traces
     * @param traceOutputfolder Output folder
     * @param round Round that action traces belong to
     * @param map_idx Map on which action traces occured
     * @param ai1_idx AI1 participating
     * @param ai2_idx AI2 participating
     * @param gs Game state
     * @param trace Action trace to zip
     * @throws IOException
     */
    private static void zipTraces(String traceOutputfolder, int round, int map_idx, int ai1_idx, int ai2_idx,
                                  GameState gs, Trace trace) throws IOException {
        TraceEntry te;
        if (traceOutputfolder != null) {
            File folder = new File(traceOutputfolder);
            if (!folder.exists()) folder.mkdirs();
            te = new TraceEntry(gs.getPhysicalGameState().clone(), gs.getTime());
            trace.addEntry(te);
            XMLWriter xml;
            ZipOutputStream zip = null;
            String filename = ai1_idx + "-vs-" + ai2_idx + "-" + map_idx + "-" + round;
            filename = filename.replace("/", "");
            filename = filename.replace(")", "");
            filename = filename.replace("(", "");
            filename = traceOutputfolder + "/" + filename;
            zip = new ZipOutputStream(new FileOutputStream(filename + ".zip"));
            zip.putNextEntry(new ZipEntry("game.xml"));
            xml = new XMLWriter(new OutputStreamWriter(zip));
            trace.toxml(xml);
            xml.flush();
            zip.closeEntry();
            zip.close();
        }
    }

    /**
     * Give window time to repaint
     * @param gs Current game state
     * @param w Window to repaint
     */
    private static void windowSleep(GameState gs, PhysicalGameStateJFrame w) {
        if (w != null) {
            w.setStateCloning(gs);
            w.repaint();
            try {
                Thread.sleep(1);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Add action traces to output
     * @param traceOutputfolder Output folder
     * @param gs Current game state
     * @param trace Action trace to add
     * @param pa1 PlayerAction from AI1
     * @param pa2 PlayerAction from AI2
     */
    private static void addTraces(String traceOutputfolder, GameState gs, Trace trace, PlayerAction pa1, PlayerAction pa2) {
        TraceEntry te;
        if (traceOutputfolder != null && (!pa1.isEmpty() || !pa2.isEmpty())) {
            te = new TraceEntry(gs.getPhysicalGameState().clone(), gs.getTime());
            te.addPlayerAction(pa1.clone());
            te.addPlayerAction(pa2.clone());
            trace.addEntry(te);
        }
    }

    /**
     * Create folders for AIs to read/write from
     * @param AIs List of AIs participating in tournament
     * @param maps Maps used in tournament
     * @param folderForReadWriteFolders Root folder to put other folders in
     * @param readWriteFolders Names for folders
     * @param firstPreAnalysis If pre analysis is enabled for pair
     */
    private static void createAIReadWriteFolders(List<AI> AIs, List<String> maps, String folderForReadWriteFolders,
                                                 String[] readWriteFolders, boolean[][] firstPreAnalysis) {
        for (int i = 0; i<AIs.size(); i++) {
            readWriteFolders[i] = folderForReadWriteFolders + "/AI" + i + "readWriteFolder";
            File f = new File(readWriteFolders[i]);
            f.mkdir();
            for (int j = 0; j<maps.size(); j++) {
                firstPreAnalysis[i][j] = true;
            }
        }
    }

    /**
     * Perform pre game analysis
     * @param preAnalysisBudgetFirstTimeInAMap Budget allowed when playing first time in a map
     * @param preAnalysisBudgetRestOfTimes Budget allowed before each game (not first time in a map)
     * @param preAnalysis If pre analysis is allowed at all
     * @param progress Writer for progress
     * @param readWriteFolders Location for read/write folders
     * @param firstPreAnalysis If pair is allowed to do pre analysis
     * @param map_idx Map currently used
     * @param ai1_idx ID of AI1
     * @param ai2_idx ID of AI2
     * @param ai1 AI1 object
     * @param ai2 AI2 object
     * @param gs Current game state
     * @throws Exception possible I/O exception
     */
    private static void preAnalysis(long preAnalysisBudgetFirstTimeInAMap, long preAnalysisBudgetRestOfTimes,
                                    boolean preAnalysis, Writer progress, String[] readWriteFolders,
                                    boolean[][] firstPreAnalysis, int map_idx, int ai1_idx, int ai2_idx, AI ai1, AI ai2,
                                    GameState gs) throws Exception {
        if (preAnalysis) {
            long preTime1 = preAnalysisBudgetRestOfTimes;
            if (firstPreAnalysis[ai1_idx][map_idx]) {
                preTime1 = preAnalysisBudgetFirstTimeInAMap;
                firstPreAnalysis[ai1_idx][map_idx] = false;
            }
            long pre_start1 = System.currentTimeMillis();
            ai1.preGameAnalysis(gs, preTime1, readWriteFolders[ai1_idx]);
            long pre_end1 = System.currentTimeMillis();
            if (progress != null) {
                progress.write("preGameAnalysis player 1 took " + (pre_end1 - pre_start1) + "\n");
                if ((pre_end1 - pre_start1)>preTime1) progress.write("TIMEOUT PLAYER 1!\n");
            }
            long preTime2 = preAnalysisBudgetRestOfTimes;
            if (firstPreAnalysis[ai2_idx][map_idx]) {
                preTime2 = preAnalysisBudgetFirstTimeInAMap;
                firstPreAnalysis[ai2_idx][map_idx] = false;
            }
            long pre_start2 = System.currentTimeMillis();
            ai2.preGameAnalysis(gs, preTime2, readWriteFolders[ai2_idx]);
            long pre_end2 = System.currentTimeMillis();
            if (progress != null) {
                progress.write("preGameAnalysis player 2 took " + (pre_end2 - pre_start2) + "\n");
                if ((pre_end2 - pre_start2)>preTime2) progress.write("TIMEOUT PLAYER 2!\n");
            }
        }
    }

    /**
     * Method to check if an agent changed the actual game state given for information only.
     * @param gs  Game State passed to the agent
     * @param clone  Cloned game state containing correct information before agent processing
     * @param playerID  ID of the player taking actions
     */
    public static void checkCheating(GameState gs, GameState clone, int playerID) {
        for (Unit u: gs.getUnits()) {
            Unit correctUnitState = clone.getUnit(u.getID());
            if (!u.checkEquals(correctUnitState)) {
                System.out.println("Game state changed. Player " + playerID + " trying to cheat, reverting!");
                u.setState(correctUnitState);
            }
        }
        if (gs.getTime() != clone.getTime()) {
            System.out.println("Game state changed. Player " + playerID + " trying to cheat, exiting!");
            System.exit(1);
        }
    }
}
