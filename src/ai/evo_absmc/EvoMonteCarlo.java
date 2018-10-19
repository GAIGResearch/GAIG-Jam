/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ai.evo_absmc;

import ai.RandomBiasedAI;
import ai.abstraction.AbstractAction;
import ai.abstraction.pathfinding.PathFinding;
import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.InterruptibleAI;
import ai.core.ParameterSpecification;
import ai.evaluation.EvaluationFunction;
import ai.evaluation.SimpleSqrtEvaluationFunction3;
import rts.GameState;
import rts.PlayerAction;
import rts.ResourceUsage;
import rts.UnitAction;
import rts.units.Unit;
import rts.units.UnitTypeTable;

import java.util.*;

/**
 *
 * @author santi
 */
public class EvoMonteCarlo extends AIWithComputationBudget implements InterruptibleAI {


    private int playerForThisComputation;
    private UnitTypeTable utt;
    private PathFinding pf;
    private Set<PlayerActionTableEntry> actions;  // A list with all the actions explored.
    private Random r;  // Random number generator.
    private PlayerAbstractActionGenerator  moveGenerator;
    private GameState gsToStartFrom; //Starting game state

    // statistics:
    private long totalRuns = 0;
    private long totalCyclesExecuted = 0;
    private long totalActionsIssued = 0;
    private boolean choicesThisCycle;

    // Params
    private boolean computeFromStats = false;
    private double mutationRate = 0.2;
    private long maxActions = -1;
    private int maxSimulationTime = 100;
    private AI randomAI = new RandomBiasedAI();  // The roll-out policy
    private EvaluationFunction ef = new SimpleSqrtEvaluationFunction3();  // Function to evaluate the quality of a state after a rollout.

    // Solution
    private PlayerActionTableEntry best;

    public EvoMonteCarlo(UnitTypeTable utt, PathFinding pf) {
        this(100, -1, utt, pf);
    }

    /**
     * Creates the monte carlo player.
     * @param availableTime Time available to make a decision
     * @param playoutsPerCycle Number of iterations allowed for the algorithm.
     * @param utt Unit Type Table object
     */
    private EvoMonteCarlo(int availableTime, int playoutsPerCycle, UnitTypeTable utt, PathFinding pf) {
        super(availableTime, playoutsPerCycle);
        r = new Random();
        this.utt = utt;
        this.pf = pf;
        this.moveGenerator = new PlayerAbstractActionGenerator(utt, pf);
    }

    public void printStats() {
        if (totalCyclesExecuted >0 && totalActionsIssued >0) {
            System.out.println("Average runs per cycle: " + ((double) totalRuns)/ totalCyclesExecuted);
            System.out.println("Average runs per action: " + ((double) totalRuns)/ totalActionsIssued);
        }
    }
    
    public void reset() {
        moveGenerator = null;
        actions = new HashSet<>();
        gsToStartFrom = null;
    }
    
    public AI clone() {
        return new EvoMonteCarlo(TIME_BUDGET, ITERATIONS_BUDGET, utt, pf);
    }

    /**
     * Gets an action at every tick
     * @param player ID of the player to move. Use it to check whether units are yours or enemy's
     * @param gs the game state where the action should be performed
     * @return PlayerAction with all actions for this step.
     * @throws Exception from monte carlo methods
     */
    public final PlayerAction getAction(int player, GameState gs) throws Exception
    {
        //Only execute an action if the player can execute any.
        if (gs.canExecuteAnyAction(player)) {

            //Reset MonteCarlo
            startNewComputation(player,gs);

            if(choicesThisCycle)
            {
                //Iterate MC as much as possible according to budget
                computeDuringOneGameFrame();

                //Decide on the best action and return it
                getBestActionSoFar();
            }


            //Translate abstract actions into unit actions for each unit.
            return moveGenerator.translateActions(player, gs);

        } else {
            //Nothing to do: empty player action
            return new PlayerAction();        
        }       
    }

    /**
     * Resets Monte Carlo to start a new search.
     * @param a_player the current player
     * @param gs the game state where the action will be taken
     */
    public void startNewComputation(int a_player, GameState gs) {

        best = null;
        playerForThisComputation = a_player;
        gsToStartFrom = gs;
        actions = new HashSet<>();
        choicesThisCycle = moveGenerator.reset(gs, playerForThisComputation);
        if(choicesThisCycle)
            moveGenerator.randomizeOrder();
    }    
    
    public void resetSearch() {
        gsToStartFrom = null;
        moveGenerator = null;
        best = null;
        actions = new HashSet<>();
    }

    /**
     * Iterates
     * @throws Exception from monte carlo run
     */
    public void computeDuringOneGameFrame() throws Exception {
        long start = System.currentTimeMillis();
        int nRuns = 0;

        // Until the budget is over, do another monte carlo rollout.
        while (true) {
            if (TIME_BUDGET > 0 && (System.currentTimeMillis() - start) >= TIME_BUDGET) break;
            if (ITERATIONS_BUDGET > 0 && nRuns >= ITERATIONS_BUDGET) break;
            monteCarloEvoRun(playerForThisComputation, gsToStartFrom);
            nRuns++;
        }

        totalCyclesExecuted++;
    }

    /**
     * Executes a monte carlo rollout.
     * @param player  this player
     * @param gs state to roll the state from.
     * @throws Exception if occuring in simulation
     */
    private void monteCarloEvoRun(int player, GameState gs) throws Exception {
        // Take the next ActionTableEntry to execute
        PlayerActionTableEntry pate;

        if (best == null) {
            // Initialize to random
            pate = new PlayerActionTableEntry();
            moveGenerator.getRandom(pate.abs);
            actions.add(pate);
        } else {
            pate = mutate(best);
            actions.add(pate);
        }

        // Given the current game state, execute the starting PlayerAction and clone the state
        GameState gs2 = gs.cloneIssue(pate.getPA(gs, moveGenerator.base_ru));

        //Make a copy of the resultant state for the rollout
        GameState gs3 = gs2.clone();

        //Perform random actions until time is up for a simulation or the game is over.
        simulate(gs3,gs3.getTime() + maxSimulationTime);

        //time holds the difference in time ticks between the initial state and the one reached at the end.
        int time = gs3.getTime() - gs2.getTime();

        //Evaluate the state reached at the end (g3) and correct with a discount factor
        pate.value += ef.evaluate(player, 1-player, gs3)*Math.pow(0.99,time/10.0);
        pate.visitCount++;
        totalRuns++;

        if (best == null || pate.value / pate.visitCount > best.value / best.visitCount) {
            best = pate;
        }
    }

    /**
     * Simulates, according to a policy (this.randomAI), actions until the game is over or we've reached
     * the limited depth specified (time)
     * @param gs Game state to start the simulation from.
     * @param time depth, or number of game ticks, that limits the simulation.
     * @throws Exception if action received from policy throws exception
     */
    public void simulate(GameState gs, int time) throws Exception {
        boolean gameover = false;

        do {
            if (gs.isComplete()) {
                gameover = gs.cycle();
            } else {
                //Issue actions for BOTH players.
                gs.issue(randomAI.getAction(0, gs));
                gs.issue(randomAI.getAction(1, gs));
            }
        } while(!gameover && gs.getTime() < time);
    }

    private PlayerActionTableEntry mutate(PlayerActionTableEntry pate) {
        PlayerActionTableEntry pate2 = new PlayerActionTableEntry();

        HashMap<Unit, AbstractAction> abs2 = new HashMap<>();
        for (Map.Entry<Unit, AbstractAction> entry : pate.abs.entrySet()) {
            if (r.nextFloat() < mutationRate) {
                // Mutate to new random action
                abs2.put(entry.getKey(), moveGenerator.getRandom(entry.getKey()));
            } else {
                abs2.put(entry.getKey(), entry.getValue());
            }
        }

        pate2.abs = abs2;

        return pate2;
    }

    /**
     * Out of all the PlayerActions tried from the current state, this returns the one with the highest average return
     * @return the best PlayerAction found.
     */
    public PlayerAction getBestActionSoFar() {
        if (computeFromStats)
            computeBestActionFromStats();

        PlayerAction bestPA = best.getPA(gsToStartFrom, moveGenerator.base_ru);

        //Assign the best abstract action
        moveGenerator.inform(best.abs);

        return bestPA;
    }

    private void computeBestActionFromStats() {

        // Find the best. For each action in the table:
        for(PlayerActionTableEntry pate:actions) {
            //If the average return is higher (better) than the current best, keep this one as current best.
            if (best==null || (pate.value /pate.visitCount)>=(best.value /best.visitCount)) {
                best = pate;
            }
        }

        //This shouldn't happen. Essentially means there's no entry in the table. Escape by applying random actions.
        if (best==null) {
            EvoMonteCarlo.PlayerActionTableEntry pate = new EvoMonteCarlo.PlayerActionTableEntry();
            moveGenerator.getRandom(pate.abs);
            System.err.println("MonteCarlo.getBestActionSoFar: best action was null!!! action.size() = " + actions.size());
        }
        totalActionsIssued++;

    }

    
    
    public String toString() {
        return getClass().getSimpleName() + "(" + TIME_BUDGET + "," + ITERATIONS_BUDGET + "," + maxSimulationTime + "," + maxActions + ", " + randomAI + ", " + ef + ")";
    }

    @Override
    public List<ParameterSpecification> getParameters() {
        List<ParameterSpecification> parameters = new ArrayList<>();
        
        parameters.add(new ParameterSpecification("TimeBudget",int.class,100));
        parameters.add(new ParameterSpecification("IterationsBudget",int.class,-1));
        parameters.add(new ParameterSpecification("PlayoutLookahead",int.class,100));
        parameters.add(new ParameterSpecification("MaxActions",long.class,100));
        parameters.add(new ParameterSpecification("playoutAI",AI.class, randomAI));
        parameters.add(new ParameterSpecification("EvaluationFunction", EvaluationFunction.class, new SimpleSqrtEvaluationFunction3()));
        parameters.add(new ParameterSpecification("ComputeFromStats", boolean.class,false));
        parameters.add(new ParameterSpecification("MutationRate", double.class,0.2));

        return parameters;
    }


    // Inner class to hold cumulative score (value) and number of rollouts (visitCount)
    // that started from a PlayerAction (pa)
    public class PlayerActionTableEntry {
        float value = 0;
        int visitCount = 0;
        public HashMap<Unit, AbstractAction> abs = new HashMap<>();

        /**
         * Transform abstract actions into PlayerAction
         * @param gameState - current game state
         * @param baseRU - base resource usage
         * @return PlayerAction
         */
        PlayerAction getPA(GameState gameState, ResourceUsage baseRU) {
            PlayerAction pa = new PlayerAction();
            pa.setResourceUsage(baseRU.clone());

            for (Map.Entry<Unit, AbstractAction> entry : abs.entrySet()) {
                Unit u = entry.getKey();
                AbstractAction aa = entry.getValue();

                if (aa != null) {
                    GameState gsCopy = gameState.clone();
                    UnitAction ua = aa.execute(gsCopy);

                    if (ua != null) {
                        ResourceUsage r2 = ua.resourceUsage(u, gameState.getPhysicalGameState());

                        if (pa.getResourceUsage().consistentWith(r2, gameState)) {
                            pa.getResourceUsage().merge(r2);
                            pa.addUnitAction(u, ua);
                        }
                    }
                }
            }

            return pa;
        }

        @Override
        public String toString() {
            return abs.toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof PlayerActionTableEntry) {
                return value == ((PlayerActionTableEntry) obj).value && visitCount == ((PlayerActionTableEntry) obj).visitCount
                        && abs.equals(((PlayerActionTableEntry)obj).abs);
            }
            return false;
        }
    }
}
