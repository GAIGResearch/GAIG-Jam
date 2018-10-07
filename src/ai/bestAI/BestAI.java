package ai.bestAI;

import ai.abstraction.*;
import ai.abstraction.pathfinding.BFSPathFinding;
import ai.abstraction.pathfinding.PathFinding;
import ai.core.AI;
import ai.core.ParameterSpecification;
import ai.evaluation.EvaluationFunction;
import rts.*;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;
import util.Pair;

import java.util.*;

import static ai.bestAI.BestUtilities.buildBuilding;
import static ai.bestAI.BestUtilities.checkUnits;

public class BestAI extends AI {

    private Random gen;
    private List<Unit> listOfUnits;
    private Map<Unit, AbstractAction> activeActions;
    private Map<Integer,List<AbstractAction>> listOfActions;
    private Map<Integer,Integer> countOfActions;

    private ArrayList<Unit> workerList;
    private ArrayList<Unit> baseList;
    private ArrayList<Unit> barrackList;
    private ArrayList<Unit> fighterList;
    private List<Integer> reservedPositions;

    private ArrayList<Pair<Integer, Integer>> available;

    private EvaluationFunction evaluator;
    private UnitType workerType, lightType, heavyType, rangedType, baseType, barracksType;
    private PathFinding a_pf;
    protected UnitTypeTable utt;
    private int harvesters = 0;

    private boolean done;
    private int simulations = 200;
    private int MIN_RESOURCE_WAIT_BASE = 5;
    private int MIN_WORKERS_WAIT_BASE = 5;
    private int MIN_TICK_WAIT_BASE = 500;
    private int MIN_TICK_ATTACK_ONLY = 2500;
    private int ATTACK_DISTANCE = 5;
    private int MAX_HARVESTERS = 4;
    private float MUTATION_RATE = 0.3f;

    public BestAI(UnitTypeTable a_utt) {
        this(a_utt, new BFSPathFinding());
    }

    public BestAI(UnitTypeTable a_utt, PathFinding a_pf) {
        gen = new Random();
        listOfUnits = new ArrayList<>();

        workerList = new ArrayList<>();
        baseList = new ArrayList<>();
        barrackList = new ArrayList<>();
        fighterList = new ArrayList<>();

        activeActions = new HashMap<>();
        listOfActions = new HashMap<>();
        countOfActions = new HashMap<>();
        evaluator = new BestEvaluationFunction();
        utt = a_utt;
        if (utt != null) {
            workerType = utt.getUnitType("Worker");
            lightType = utt.getUnitType("Light");
            heavyType = utt.getUnitType("Heavy");
            rangedType = utt.getUnitType("Ranged");
            baseType = utt.getUnitType("Base");
            barracksType = utt.getUnitType("Barracks");
        }
        done = false;
        this.a_pf = a_pf;

        reservedPositions = new LinkedList<>();
    }

    @Override
    public void reset() {

    }

    @Override
    public PlayerAction getAction(int player, GameState gs) throws Exception {
        // Keep track of time
        long startTime = System.nanoTime();

        // Determine number of max harvesters according to current numer of resources available in the game
        MAX_HARVESTERS = 0;
        for (Unit u:gs.getUnits()) {
            if (u.getType().isResource) {
                MAX_HARVESTERS++;
            }
        }

        // Count how many active harvesters there currently are
        harvesters = 0;
        for (AbstractAction aa:activeActions.values()) {
            if (aa instanceof BestHarvest) {
                harvesters++;
            }
        }

        // Initialize variables for this tick
        PhysicalGameState pgs = gs.getPhysicalGameState();
        PlayerAction pa = new PlayerAction();

        // If no actions can be executed this tick, don't execute actions (duh!)
        if (!gs.canExecuteAnyAction(player)) return pa;

        // Update current resource usage in the PlayerAction object
        updatePA(gs, pa);

        // Find lists of units in current tick, separate into workers, bases, barracks and fighters
        listOfUnits = new ArrayList<>();
        List<Unit> gameUnits = pgs.getUnits();
        for(Unit u : gameUnits) {
            if (u.getPlayer() == player) {
                // Check our units are still in play:
                checkUnits(workerList, gameUnits);
                checkUnits(baseList, gameUnits);
                checkUnits(barrackList, gameUnits);
                checkUnits(fighterList, gameUnits);

                // Add new units to our lists
                UnitType type = u.getType();
                if (type == workerType && !workerList.contains(u)) {
                    workerList.add(u);
                } else if (type == baseType && !baseList.contains(u)) {
                    baseList.add(u);
                } else if (type == barracksType && !barrackList.contains(u)) {
                    barrackList.add(u);
                } else if ((type == lightType || type == heavyType || type == rangedType) && !fighterList.contains(u)) {
                    // If other fighter units are added, this will ignore those
                    fighterList.add(u);
                }
            }
        }

        // Initialize lists of actions
        listOfActions = new HashMap<>();
        countOfActions = new HashMap<>();

        // Add actions available for all unit types
//        System.out.println("Workers: " + workerList.size());
//        System.out.println("Buildings: " + (baseList.size() + barrackList.size()));
//        System.out.println("Fighters: " + fighterList.size());
        int idx = 0;
        idx = addWorkerActions(idx, gs, pa, player);
        idx = addBaseActions(idx, gs, pa);
        idx = addBarracksActions(idx, gs, pa);
        idx = addFighterActions(idx, gs, pa, player);

        // Add actions for all units not already covered in defined types
        for(Unit u : gameUnits) {
            if (u.getPlayer() == player) {
                if (gs.getActionAssignment(u) == null) {
                    if (!workerList.contains(u) && !baseList.contains(u) && !barrackList.contains(u) && !fighterList.contains(u)) {
                        listOfUnits.add(u);
//                        List<AbstractAction> l = u.getUnitActions(gs);
                        List<AbstractAction> l = new ArrayList<>();
                        l.add(new Idle(u));
                        listOfActions.put(idx, l);
                        countOfActions.put(idx, l.size());
                        idx ++;
                    }
                }
            }
        }

        // the length of the set of actions evolved is the number of units that can receive new actions
        int length = listOfUnits.size();

        // Initialize individual randomly
        int[] individual = new int[length];
        generateRandomInd(individual);

        // Evaluate individual. Issue the actions contained to a copy of the game, then simulate a number of game cycles
        // Evaluate the resultant game state.
        GameState model = gs.clone();
        model.issue(indToPlayerAction(individual, model));
        for (int i = 0; i < simulations && !model.gameover(); i++) {
            model.cycle();
        }
        double value = evaluator.evaluate(player,1-player, model);

        // Evolve individual
        int iteration = 0;
        while ((System.nanoTime() - startTime) / 1000000 < 100) { // While there's budget

            // Make a copy of the current individual and mutate it
            int[] individual1 = individual.clone();
            mutateIndividual(individual1);
//            generateRandomInd(individual1); // Or create a new random individual

            // Evaluate new individual. Same process as before.
            model = gs.clone();
            model.issue(indToPlayerAction(individual1, model));
            for (int i = 0; i < simulations && !model.gameover(); i++) {
                model.cycle();
            }
            double value1 = evaluator.evaluate(player, 1 - player, model);

//            if (!done && gs.getTime() > 150) {
//                // This is used to print the evolved individual during one game tick only
//                System.out.println(Arrays.toString(individual));
//                System.out.println(value1 + " " + value);
//            }

            // Check if the new individual is better than previous. If so, discard previous individual.
            if (value1 > value) {
                individual = individual1;
                value = value1;
            }
            iteration++;
        }
//        System.out.println("Number of iterations in game tick " + gs.getTime() + ": " + iteration);

//        if (gs.getTime() > 150)
//            // This is used to print the evolved individual during one game tick only
//            done = true;

        // Final individual is what we'll play in the game.
        pa = indToPlayerAction(individual, gs);

//        System.out.println("Player action at game tick " + gs.getTime() + ": " + pa);

        return pa;
    }

    @Override
    public AI clone() {
        BestAI clone = new BestAI(utt, a_pf);
        clone.gen = gen;
        clone.listOfUnits = new ArrayList<>(listOfUnits);
        clone.activeActions = new HashMap<>(activeActions);
        clone.listOfActions = new HashMap<>(listOfActions);
        clone.countOfActions = new HashMap<>(countOfActions);
        clone.workerList = new ArrayList<>(workerList);
        clone.baseList = new ArrayList<>(baseList);
        clone.barrackList = new ArrayList<>(barrackList);
        clone.fighterList = new ArrayList<>(fighterList);
        clone.reservedPositions = new ArrayList<>(reservedPositions);
        clone.harvesters = harvesters;
        clone.done = done;
        clone.MAX_HARVESTERS = MAX_HARVESTERS;
        return clone;
    }

    /**
     * Method to add action to PlayerAction plan while checking consistency in resource usage
     */
    private void addAction(PlayerAction pa, Unit u, UnitAction ua, GameState gs, PhysicalGameState pgs) {
        if (ua.resourceUsage(u, pgs).consistentWith(pa.getResourceUsage(), gs)) {
            ResourceUsage ru = ua.resourceUsage(u, pgs);
            pa.getResourceUsage().merge(ru);
            pa.addUnitAction(u, ua);
        }
    }

    /**
     * Method to generate a random set of actions
     */
    private void generateRandomInd(int[] individual){
        for (int i = 0; i < listOfUnits.size(); i++) {
            int actions = 0;
            if (countOfActions != null && i < countOfActions.size()) {
                actions = countOfActions.get(i);
            }
            if (actions != 0) {
                individual[i] = gen.nextInt(actions);
            } else {
                individual[i] = -1;
            }
        }
    }

    /**
     * Method to mutate individual
     */
    private void mutateIndividual(int[] individual) {
        try {
            for (int i = 0; i < individual.length; i++) {
                float mutate = gen.nextFloat();
                if (mutate < MUTATION_RATE) {
                    if (countOfActions.get(i) > 0) {
                        individual[i] = gen.nextInt(countOfActions.get(i));
                    }
                }
            }
        } catch(Exception ignored) {}
    }

    /**
     * Method to translate an individual to PlayerAction object
     */
    private PlayerAction indToPlayerAction(int[] individual, GameState gs) {
        PlayerAction pa = new PlayerAction();
        updatePA(gs,pa);
        for (int i = 0; i < listOfUnits.size(); i++) {
            if (individual[i] != -1) {
                UnitAction ua = listOfActions.get(i).get(individual[i]).execute(gs);
                if (ua != null) {
                    addAction(pa, mapUnitToClone(listOfUnits.get(i), gs), ua, gs, gs.getPhysicalGameState());
                }
            } else {
                break;
            }
        }
        return pa;
    }

    /**
     * Method to find the right Unit object in cloned game state
     * @param u original unit
     * @param clone cloned game state
     * @return cloned unit (has same ID)
     */
    private Unit mapUnitToClone(Unit u, GameState clone) {
        return clone.getUnit(u.getID());
    }

    /**
     * Method to update PlayerAction object with current resource usage
     */
    private void updatePA(GameState gs, PlayerAction pa){
        // Generate the reserved resources:
        for(Unit u : gs.getPhysicalGameState().getUnits()) {
            UnitActionAssignment uaa = gs.getActionAssignment(u);
            if (uaa != null) {
                ResourceUsage ru = uaa.action.resourceUsage(u, gs.getPhysicalGameState());
                pa.getResourceUsage().merge(ru);
            }
        }
    }

    /**
     * Method to add worker actions. Maximum number of workers will harvest, the rest will defend base / move randomly
     * Or attack if enemy close or build buildings if needed.
     */
    private int addWorkerActions(int idx, GameState gs, PlayerAction pa, int player){
        PhysicalGameState pgs = gs.getPhysicalGameState();
        for (Unit u: workerList) {
            if (gs.getActionAssignment(u) == null) {
                List<AbstractAction> l = new ArrayList<>();
                // A worker with nothing to do
                // Check if it has any active abstract actions. Just keep executing that if so.
                if (activeActions.containsKey(u)) {
                    AbstractAction aa = activeActions.get(u);
                    if (aa.completed(gs)) {
                        activeActions.remove(u);
                    } else {
                        UnitAction ua = aa.execute(gs);
                        addAction(pa, u, ua, gs, pgs);
                    }
                } else {
                    // If not, add it to listOfUnits and its potential actions are:
                    // - attack if enemy close
                    listOfUnits.add(u);
                    Unit closestEnemy = null;
                    int closestDistance = 0;
                    for(Unit u2:pgs.getUnits()) {
                        if (u2.getPlayer() >= 0 && u2.getPlayer() != player) {
                            int d = Math.abs(u2.getX() - u.getX()) + Math.abs(u2.getY() - u.getY());
                            if (closestEnemy == null || d < closestDistance) {
                                closestEnemy = u2;
                                closestDistance = d;
                            }
                        }
                    }
                    if (closestEnemy != null && (closestDistance < ATTACK_DISTANCE || harvesters >= MAX_HARVESTERS)) {
                        l.add(new Attack(u,closestEnemy,a_pf));
                    }
                    // - harvest if not enough harvesters
                    if (harvesters < MAX_HARVESTERS) {
                        Unit closestResource = null;
                        closestDistance = 0;
                        for (Unit u2 : pgs.getUnits()) {
                            if (u2.getType().isResource) {
                                int d = Math.abs(u2.getX() - u.getX()) + Math.abs(u2.getY() - u.getY());
                                if (closestResource == null || d < closestDistance) {
                                    closestResource = u2;
                                    closestDistance = d;
                                }
                            }
                        }
                        Unit closestBase = null;
                        closestDistance = 0;
                        for (Unit u2 : baseList) {
                            int d = Math.abs(u2.getX() - u.getX()) + Math.abs(u2.getY() - u.getY());
                            if (closestBase == null || d < closestDistance) {
                                closestBase = u2;
                                closestDistance = d;
                            }
                        }
                        l.add(new BestHarvest(u, closestResource, closestBase, a_pf));
                        harvesters++;
                    }

                    // - build bases and barracks
                    buildBuilding(pa, player, gs, u, l, baseList, baseType, reservedPositions, a_pf);
                    buildBuilding(pa, player, gs, u, l, barrackList, barracksType, reservedPositions, a_pf);

                    // - move to random location
                    moveToRandomPos(u, l, pgs);

                    listOfActions.put(idx,l);
                    countOfActions.put(idx,l.size());
                    idx ++;
                }
            }
        }
        return idx;
    }

    /**
     * Method to transform the free positions in the grid to lists of X and Y positions
     * @param allFree - free positions
     */
    private void freeToAvailable(boolean[][] allFree) {
        available = new ArrayList<>();
        for (int x = 0; x < allFree.length; x++) {
            for (int y = 0; y < allFree[x].length; y++) {
                if (allFree[x][y]) {
                    available.add(new Pair<>(x, y));
                }
            }
        }
    }

    /**
     * Method to choose to move to random available position on the map
     * @param u - unit
     * @param l - list of actions
     * @param pgs - physical game state object
     */
    private void moveToRandomPos(Unit u, List<AbstractAction> l, PhysicalGameState pgs) {
        boolean[][] allFree = pgs.getAllFree();
        freeToAvailable(allFree);
        Pair<Integer, Integer> choice = available.get(gen.nextInt(available.size()));
        l.add(new Move(u, choice.m_a, choice.m_b, a_pf));
    }
    /**
     * Method to add base actions. It will keep spawning workers unless no barrack was built late into the game,
     * in which case it will wait for resources instead.
     */
    private int addBaseActions(int idx, GameState gs, PlayerAction pa){
        PhysicalGameState pgs = gs.getPhysicalGameState();
        for (Unit u: baseList) {
            if (gs.getActionAssignment(u) == null) {
                List<AbstractAction> l = new ArrayList<>();
                // A base with nothing to do
                // Check if it has any active abstract actions. Just keep executing that if so.
                if (activeActions.containsKey(u)) {
                    AbstractAction aa = activeActions.get(u);
                    if (aa.completed(gs)) {
                        activeActions.remove(u);
                    } else {
                        UnitAction ua = aa.execute(gs);
                        addAction(pa, u, ua, gs, pgs);
                    }
                } else {
                    // If not, add it to listOfUnits and its potential actions are:
                    // - if resources less than needed for barracks, no barracks, enough workers and game tick large,
                    // wait for resources to construct barracks
                    listOfUnits.add(u);
                    if (u.getResources() < MIN_RESOURCE_WAIT_BASE && workerList.size() > MIN_WORKERS_WAIT_BASE
                            && gs.getTime() > MIN_TICK_WAIT_BASE && barrackList.size() == 0
                    || gs.getTime() > MIN_TICK_ATTACK_ONLY) {
                        return idx;
                    }
                    // - train worker otherwise
                    l.add(new Train(u,workerType));
                    listOfActions.put(idx, l);
                    countOfActions.put(idx, l.size());
                    idx++;
                }
            }
        }
        return idx;
    }

    /**
     * Method to add barrack actions. They train one of the 3 fighter types, or idle.
     */
    private int addBarracksActions(int idx, GameState gs, PlayerAction pa){
        PhysicalGameState pgs = gs.getPhysicalGameState();
        for (Unit u: barrackList) {
            if (gs.getActionAssignment(u) == null) {
                List<AbstractAction> l = new ArrayList<>();
                // A base with nothing to do
                // Check if it has any active abstract actions. Just keep executing that if so.
                if (activeActions.containsKey(u)) {
                    AbstractAction aa = activeActions.get(u);
                    if (aa.completed(gs)) {
                        activeActions.remove(u);
                    } else {
                        UnitAction ua = aa.execute(gs);
                        addAction(pa, u, ua, gs, pgs);
                    }
                } else {
                    // If not, add it to listOfUnits and its potential actions are:
                    // - train ranged fighters
                    // - train light fighters
                    // - train heavy fighters
                    listOfUnits.add(u);
                    l.add(new Train(u,lightType));
                    l.add(new Train(u,rangedType));
                    l.add(new Train(u,heavyType));
                    l.add(new Idle(u));
                    listOfActions.put(idx, l);
                    countOfActions.put(idx, l.size());
                    idx++;
                }
            }
        }
        return idx;
    }

    /**
     * Method to add Fighter actions. All they do at the moment is attack closest enemy.
     */
    private int addFighterActions(int idx, GameState gs, PlayerAction pa, int player){
        PhysicalGameState pgs = gs.getPhysicalGameState();
        for (Unit u: fighterList) {
            if (gs.getActionAssignment(u) == null) {
                List<AbstractAction> l = new ArrayList<>();
                // A fighter with nothing to do
                // Check if it has any active abstract actions. Just keep executing that if so.
                if (activeActions.containsKey(u)) {
                    AbstractAction aa = activeActions.get(u);
                    if (aa.completed(gs)) {
                        activeActions.remove(u);
                    } else {
                        UnitAction ua = aa.execute(gs);
                        addAction(pa, u, ua, gs, pgs);
                    }
                } else {
                    // If not, add it to listOfUnits and its potential actions are:
                    // - attack if enemy close
                    listOfUnits.add(u);
                    Unit closestEnemy = null;
                    int closestDistance = 0;
                    for(Unit u2:pgs.getUnits()) {
                        if (u2.getPlayer() >= 0 && u2.getPlayer() != player) {
                            int d = Math.abs(u2.getX() - u.getX()) + Math.abs(u2.getY() - u.getY());
                            if (closestEnemy == null || d < closestDistance) {
                                closestEnemy = u2;
                                closestDistance = d;
                            }
                        }
                    }
                    if (closestEnemy != null) {
                        l.add(new Attack(u,closestEnemy,a_pf));
                    }
                    // TODO: explore the map more smartly
                    // - move to random location
                    moveToRandomPos(u, l, pgs);

                    listOfActions.put(idx,l);
                    countOfActions.put(idx,l.size());
                    idx ++;
                }
            }
        }
        return idx;
    }

    /**
     *
     private int simulations = 50;
     private int MIN_RESOURCE_WAIT_BASE = 5;
     private int MIN_WORKERS_WAIT_BASE = 5;
     private int MIN_TICK_WAIT_BASE = 500;
     private int MAX_HARVESTERS = 4;
     private int MIN_TICK_ATTACK_ONLY = 2500;
     private int ATTACK_DISTANCE = 5;
     private float MUATION_RATE = 0.3f;
     */
    @Override
    public List<ParameterSpecification> getParameters() {
        List<ParameterSpecification> params = new ArrayList<>();
        params.add(new ParameterSpecification("Simulations", int.class, 50));
        params.add(new ParameterSpecification("ResourcesWaitBase", int.class, 5));
        params.add(new ParameterSpecification("WorkersWaitBase", int.class, 5));
        params.add(new ParameterSpecification("TickWaitBase", int.class, 500));
        params.add(new ParameterSpecification("MaxHarvesters", int.class, 4));
        params.add(new ParameterSpecification("MaxTickAttackOnly", int.class, 2500));
        params.add(new ParameterSpecification("AttackDistance", int.class, 5));
        params.add(new ParameterSpecification("MutationRate", float.class, 0.3f));
        return params;
    }

    public void setSimulations(int simulations) {
        this.simulations = simulations;
    }
    public void setResourcesWaitBase(int resourcesWaitBase) {
        this.MIN_RESOURCE_WAIT_BASE = resourcesWaitBase;
    }
    public void setWorkersWaitBase(int workersWaitBase) {
        this.MIN_WORKERS_WAIT_BASE = workersWaitBase;
    }
    public void setTickWaitBase(int tickWaitBase) {
        this.MIN_TICK_WAIT_BASE = tickWaitBase;
    }
    public void setMaxHarvesters(int maxHarvesters) {
        this.MAX_HARVESTERS = maxHarvesters;
    }
    public void setMaxTickAttackOnly(int maxTickAttackOnly) {
        this.MIN_TICK_ATTACK_ONLY = maxTickAttackOnly;
    }
    public void setAttackDistance(int attackDistance) {
        this.ATTACK_DISTANCE = attackDistance;
    }
    public void setMutationRate(float mutationRate) {
        this.MUTATION_RATE = mutationRate;
    }
}
