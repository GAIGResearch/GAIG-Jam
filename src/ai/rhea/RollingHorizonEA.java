package ai.rhea;

import ai.RandomBiasedAI;
import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.InterruptibleAI;
import ai.core.ParameterSpecification;
import ai.evaluation.EvaluationFunction;
import ai.evaluation.SimpleSqrtEvaluationFunction3;
import rts.GameState;
import rts.PlayerAction;
import rts.PlayerActionGenerator;
import rts.units.UnitTypeTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;


public class RollingHorizonEA extends AIWithComputationBudget implements InterruptibleAI {
    public static final int DEBUG = 0;

    public class PlayerActionTableEntry {
        PlayerAction pa;
        float accum_evaluation;
        int visit_count;

        PlayerActionTableEntry(){
            accum_evaluation = 0;
            visit_count = 0;
        }
    }

    private AI opponentModel;
    private EvaluationFunction ef;
    private Random r = new Random();
    private long max_actions_so_far;
    
    private PlayerActionGenerator  moveGenerator;
    private List<PlayerActionTableEntry> actions;
    private GameState gs_to_start_from;
    private int run;
    private int playerForThisComputation;
    
    // statistics:
    public long total_runs;
    public long total_cycles_executed;
    public long total_actions_issued;
        
    private long MAX_ACTIONS;
    private int MAX_SIMULATION_TIME;
    
    
    public RollingHorizonEA(UnitTypeTable utt) {
        this(100, -1, 100,
             new RandomBiasedAI(),
                new SimpleSqrtEvaluationFunction3());
    }

    
    public RollingHorizonEA(int available_time, int playouts_per_cycle, int lookahead, AI policy, EvaluationFunction a_ef) {
        super(available_time, playouts_per_cycle);
        MAX_ACTIONS = -1;
        MAX_SIMULATION_TIME = lookahead;
        ef = a_ef;
        total_runs = 0;
        total_cycles_executed = 0;
        total_actions_issued = 0;
        max_actions_so_far = 0;
        run = 0;
        opponentModel = policy;
    }

    public RollingHorizonEA(int available_time, int playouts_per_cycle, int lookahead, long pop_size, AI policy, EvaluationFunction a_ef) {
        super(available_time, playouts_per_cycle);
        MAX_ACTIONS = pop_size;
        MAX_SIMULATION_TIME = lookahead;
        ef = a_ef;
        opponentModel = policy;
    }
    
    
    public void printStats() {
        if (total_cycles_executed > 0 && total_actions_issued > 0) {
            System.out.println("Average runs per cycle: " + ((double)total_runs)/total_cycles_executed);
            System.out.println("Average runs per action: " + ((double)total_runs)/total_actions_issued);
        }
    }
    
    public void reset() {
        moveGenerator = null;
        actions = null;
        gs_to_start_from = null;
        run = 0;
    }    
    
    public AI clone() {
        return new RollingHorizonEA(TIME_BUDGET, ITERATIONS_BUDGET, MAX_SIMULATION_TIME, MAX_ACTIONS, opponentModel, ef);
    }
    
    
    public final PlayerAction getAction(int player, GameState gs) throws Exception {
        if (gs.canExecuteAnyAction(player)) {
            startNewComputation(player,gs.clone());
            computeDuringOneGameFrame();
            return getBestActionSoFar();
        } else {
            return new PlayerAction();        
        }       
    }

    
    public void startNewComputation(int a_player, GameState gs) throws Exception {
        if (DEBUG >= 2) System.out.println("Starting a new search...");
        if (DEBUG >= 2) System.out.println(gs);
        playerForThisComputation = a_player;
        gs_to_start_from = gs;
        moveGenerator = new PlayerActionGenerator(gs,playerForThisComputation);
        moveGenerator.randomizeOrder();
        actions = null;
        run = 0;
    }    
    
    
    public void resetSearch() {
        if (DEBUG >= 2) System.out.println("Resetting search...");
        gs_to_start_from = null;
        moveGenerator = null;
        actions = null;
        run = 0;
    }
    

    public void computeDuringOneGameFrame() throws Exception {
        if (DEBUG >= 2) System.out.println("Search...");
        long start = System.currentTimeMillis();
        int nruns = 0;
        long cutOffTime = (TIME_BUDGET > 0 ? System.currentTimeMillis() + TIME_BUDGET : 0);
        if (TIME_BUDGET <= 0) cutOffTime = 0;
        
        if (actions == null) {
            actions = new ArrayList<>();
            if (MAX_ACTIONS > 0 && moveGenerator.getSize() > 2 * MAX_ACTIONS) {
                for(int i = 0; i < MAX_ACTIONS; i++) {
                    PlayerActionTableEntry pate = new PlayerActionTableEntry();
                    pate.pa = moveGenerator.getRandom();
                    actions.add(pate);
                }
                max_actions_so_far = Math.max(moveGenerator.getSize(), max_actions_so_far);
                if (DEBUG >= 1) System.out.println("RollingHorizonEA for player " + playerForThisComputation + " chooses between " + moveGenerator.getSize() + " actions [maximum so far " + max_actions_so_far + "] (cycle " + gs_to_start_from.getTime() + ")");
            } else {
                PlayerAction pa;
                long count = 0;
                do {
                    pa = moveGenerator.getNextAction(gs_to_start_from, -1, cutOffTime);
                    if (pa != null) {
                        PlayerActionTableEntry pate = new PlayerActionTableEntry();
                        pate.pa = pa;
                        actions.add(pate);
                        count++;
                        if (MAX_ACTIONS > 0 && count >= 2 * MAX_ACTIONS) break; // this is needed since some times, moveGenerator.size() overflows
                    }
                } while(pa != null);
                max_actions_so_far = Math.max(actions.size(), max_actions_so_far);
                if (DEBUG >= 1) System.out.println("RollingHorizonEA (complete generation plus random reduction) for player " + playerForThisComputation + " chooses between " + actions.size() + " actions [maximum so far " + max_actions_so_far + "] (cycle " + gs_to_start_from.getTime() + ")");
                while(MAX_ACTIONS > 0 && actions.size() > MAX_ACTIONS) actions.remove(r.nextInt(actions.size()));
            }
        }

        while (true) {
            if (TIME_BUDGET > 0 && (System.currentTimeMillis() - start) >= TIME_BUDGET) break;
            if (ITERATIONS_BUDGET > 0 && nruns >= ITERATIONS_BUDGET) break;
            run(playerForThisComputation, gs_to_start_from);
            nruns++;
        }

        total_cycles_executed++;
    }


    private void run(int player, GameState gs) throws Exception {
        int idx = run % actions.size();
        PlayerActionTableEntry pate = actions.get(idx);

        GameState gs2 = gs.cloneIssue(pate.pa);
        GameState gs3 = gs2.clone();
        PlayerActionGenerator moveGeneratorClone = moveGenerator.getClone(gs3);
        evaluate(idx, gs3, moveGeneratorClone, gs3.getTime() + MAX_SIMULATION_TIME);
        int time = gs3.getTime() - gs2.getTime();

        pate.accum_evaluation += ef.evaluate(player, 1-player, gs3) * Math.pow(0.99, time/10.0);
        pate.visit_count++;
        run++;
        total_runs++;
    }


    public PlayerAction getBestActionSoFar() {
        // find the best:
        PlayerActionTableEntry best = null;
        for(PlayerActionTableEntry pate:actions) {
            if (best == null || (pate.accum_evaluation / pate.visit_count) > (best.accum_evaluation / best.visit_count)) {
                best = pate;
            }
        }
        if (best == null) {
            PlayerActionTableEntry pate = new PlayerActionTableEntry();
            pate.pa = moveGenerator.getRandom();
            best = pate;
            System.err.println("RollingHorizonEA.getBestActionSoFar: best action was null!!! action.size() = " + actions.size());
        }
        
        if (DEBUG >= 1) {
            System.out.println("Executed " + run + " runs");
            System.out.println("Selected action: " + best + " visited " + best.visit_count + " with average evaluation " + (best.accum_evaluation / best.visit_count));
        }      
        
        total_actions_issued++;
        
        return best.pa;        
    }
    
    
    public void evaluate(int idx, GameState gs, PlayerActionGenerator moveGenerator, int time) throws Exception {
        boolean gameover = false;

        do {
            if (gs.isComplete()) {
                gameover = gs.cycle();
            } else {
                gs.issue(moveGenerator.getNextAction(gs, idx, gs.getTime()));  // Get our action
                gs.issue(opponentModel.getAction(1, gs));  // Get opponent action, random biased model
            }
        } while(!gameover && gs.getTime() < time);
    }
    
    
    public String toString() {
        return getClass().getSimpleName() + "(" + TIME_BUDGET + "," + ITERATIONS_BUDGET + "," + MAX_SIMULATION_TIME + "," + MAX_ACTIONS + ", " + ef + ")";
    }
    
    
    @Override
    public List<ParameterSpecification> getParameters()
    {
        List<ParameterSpecification> parameters = new ArrayList<>();
        
        parameters.add(new ParameterSpecification("TimeBudget", int.class,100));
        parameters.add(new ParameterSpecification("IterationsBudget", int.class,-1));
        parameters.add(new ParameterSpecification("PlayoutLookahead", int.class,100));
        parameters.add(new ParameterSpecification("MaxActions", long.class,100));
        parameters.add(new ParameterSpecification("EvaluationFunction", EvaluationFunction.class, new SimpleSqrtEvaluationFunction3()));
        
        return parameters;
    }       
    
    
    public int getPlayoutLookahead() {
        return MAX_SIMULATION_TIME;
    }
    
    
    public void setPlayoutLookahead(int a_pola) {
        MAX_SIMULATION_TIME = a_pola;
    }


    public long getMaxActions() {
        return MAX_ACTIONS;
    }
    
    
    public void setMaxActions(long a_ma) {
        MAX_ACTIONS = a_ma;
    }


    public EvaluationFunction getEvaluationFunction() {
        return ef;
    }
    
    
    public void setEvaluationFunction(EvaluationFunction a_ef) {
        ef = a_ef;
    }      
}
