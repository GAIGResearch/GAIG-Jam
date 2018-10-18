package ai.evo_absmc;

import ai.abstraction.*;
import ai.abstraction.pathfinding.PathFinding;
import ai.core.AI;
import ai.core.ParameterSpecification;
import rts.*;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;
import util.Pair;

import java.util.*;

public class PlayerAbstractActionGenerator extends AbstractionLayerAI{
    private Random r;

    private GameState gameState;
    private PhysicalGameState physicalGameState;
    ResourceUsage base_ru;
    private List<Pair<Unit,List<AbstractAction>>> choices;

    private long size;  // This will be capped at Long.MAX_VALUE;
    private long generated;
    private int currentChoice[];

    // Variables to hold all types of units (this is useful for functions, and also
    // faster than comparing Strings.
    public UnitType workerType, baseType, barracksType;
    public UnitType rangedType, lightType, heavyType;
    private UnitType resourceType;

    // Helper class to create abstract actions
    private AbstractActionBuilder actBuilder;
    private int displacement = 3;

    // We count here how many units of each type we have.
    HashMap<UnitType, Integer> typeCount;

    /**
     * Generating all possible actions for a player in a given state
     */
    PlayerAbstractActionGenerator(UnitTypeTable utt, PathFinding aPf) {
        super(aPf, -1,-1);

        //Get unit types.
        workerType = utt.getUnitType("Worker");
        baseType = utt.getUnitType("Base");
        barracksType = utt.getUnitType("Barracks");
        rangedType = utt.getUnitType("Ranged");
        lightType = utt.getUnitType("Light");
        heavyType = utt.getUnitType("Heavy");
        resourceType = utt.getUnitType("Resource");

    }

    public boolean reset(GameState a_gs, int pID) {
        r = new Random();

        actions = new LinkedHashMap<>();
        size = 1;  // this will be capped at Long.MAX_VALUE;
        generated = 0;
        currentChoice = null;

        // Generate the reserved resources:
        base_ru = new ResourceUsage();
        gameState = a_gs;
        physicalGameState = gameState.getPhysicalGameState();
        actBuilder = new AbstractActionBuilder(this, pf, pID);

        this.determineCounts(a_gs, pID);

        for (Unit u : physicalGameState.getUnits()) {
            UnitActionAssignment uaa = gameState.getUnitActions().get(u);
            if (uaa != null) {
                ResourceUsage ru = uaa.action.resourceUsage(u, physicalGameState);
                base_ru.merge(ru);
            }
        }

        //This will hold all the actions that can be executed by this player.
        choices = new ArrayList<>();
        for (Unit u : physicalGameState.getUnits()) {
            if (u.getPlayer() == pID) {
                if ((gameState.getUnitActions().get(u) == null) && (this.getAbstractAction(u) == null))
                {

                    //List of all the actions this unit can execute (in the given game state).
                    List<AbstractAction> l = getAbstractActions(gameState, u);


                    // unit u can do actions in list l. Add to the list of choices.
                    choices.add(new Pair<>(u, l));

                    // make sure we don't overflow (Long.MAX_VALUE/size is the maximum number of pairs we want to consider)
                    long tmp = l.size();
                    if (Long.MAX_VALUE / size <= tmp) {
                        size = Long.MAX_VALUE;
                    } else {
                        size *= (long) l.size();
                    }
                }
            }
        }

        //This shouldn't happen. If I have no units, I should've lost, and all units should have NONE as a possible action
        if (choices.size() != 0) {
            //We compute the number of possible actions per unit, plus another array to select a current choice for each one of them
            currentChoice = new int[choices.size()];
            int i = 0;
            for(Pair<Unit,List<AbstractAction>> ignored :choices) {
                currentChoice[i] = 0;
                i++;
            }

            return true;
        }

        return false;
    }

    /**
     * This function fills the hashmap 'typeCount' with the number of units of each type.
     * It also counts how many resource units are in the map.
     * @param gs The current game state.
     */
    private void determineCounts(GameState gs, int player)
    {
        typeCount = new HashMap<>();
        //All available units
        for(Unit u:gs.getUnits()) {
            //If it's our unit or of type resource
            if (u.getPlayer() == player || u.getType() == resourceType) {
                int value = 0;
                if(typeCount.containsKey(u.getType()))
                    value = typeCount.get(u.getType());

                //Add one to this type.
                typeCount.put(u.getType(), value + 1);
            }
        }
    }

    private List<AbstractAction> getAbstractActions(GameState gs, Unit u) {

        List<AbstractAction> actions = new ArrayList<>();

        //Depending on the type, we check for possible actions
        if(u.getType() == workerType)
        {
            //HARVEST, MOVE, BUILD, ATTACK
            HarvestSingle h = actBuilder.harvestAction(gs, u);
            if (h != null) actions.add(h);

//            Attack att = actBuilder.meleeUnitBehavior(gs, u);
//            if (att != null) actions.add(att);

            Build b = actBuilder.buildAction(gs, u, barracksType, displacement);
            if (b != null) actions.add(b);

        }else if(u.getType() == baseType)
        {
            // TRAIN WORKERS
            Train t = actBuilder.trainAction(gs, u, workerType);
            if (t != null) actions.add(t);

        }else if(u.getType() == barracksType)
        {
            // TRAIN FIGHTING UNITS
            Train t = actBuilder.trainAction(gs, u, lightType);
            if (t != null) actions.add(t);

            t = actBuilder.trainAction(gs, u, heavyType);
            if (t != null) actions.add(t);

            t = actBuilder.trainAction(gs, u, rangedType);
            if (t != null) actions.add(t);

        }else if(u.getType() == lightType || u.getType() == heavyType || u.getType() == rangedType)
        {
            // ATTACK
            Attack att = actBuilder.meleeUnitBehavior(gs, u);
            if (att != null) actions.add(att);
        }

        if(u.getType() != workerType) {
            //We always can do Idle
            Idle idle = new Idle(u);
            actions.add(idle);
        }

        return actions;
    }


    /**
     * Shuffles the list of choices
     */
    void randomizeOrder() {
        for (Pair<Unit, List<AbstractAction>> choice : choices) {
            Collections.shuffle(choice.m_b);
        }
    }

    /**
     * Returns a random player action for the game state in this object
     * @return random player action
     */
    public PlayerAction getRandom(HashMap<Unit, AbstractAction> _abs) {
        _abs.clear();
        PlayerAction pa = new PlayerAction();
        pa.setResourceUsage(base_ru.clone());
        for (Pair<Unit, List<AbstractAction>> unitChoices : choices) {
            List<AbstractAction> l = new LinkedList<>(unitChoices.m_b);
            Unit u = unitChoices.m_a;

            boolean consistent = false;
            do {
                AbstractAction aa = l.remove(r.nextInt(l.size()));

                GameState gsCopy = gameState.clone();
                UnitAction ua = aa.execute(gsCopy);

                if (ua != null) {
                    ResourceUsage r2 = ua.resourceUsage(u, physicalGameState);

                    if (pa.getResourceUsage().consistentWith(r2, gameState)) {
                        pa.getResourceUsage().merge(r2);
                        pa.addUnitAction(u, ua);
                        consistent = true;
                        _abs.put(u, aa);
                    }
                }
            } while (l.size() > 0 && !consistent);
        }
        return pa;
    }

    public AbstractAction getRandom(Unit u) {
        PlayerAction pa = new PlayerAction();
        pa.setResourceUsage(base_ru.clone());
        for (Pair<Unit, List<AbstractAction>> unitChoices : choices) {
            if (unitChoices.m_a.getID() == u.getID()) {
                List<AbstractAction> l = new LinkedList<>(unitChoices.m_b);
                do {
                    AbstractAction aa = l.remove(r.nextInt(l.size()));

                    GameState gsCopy = gameState.clone();
                    UnitAction ua = aa.execute(gsCopy);

                    if (ua != null) {
                        ResourceUsage r2 = ua.resourceUsage(u, physicalGameState);

                        if (pa.getResourceUsage().consistentWith(r2, gameState)) {
                            return aa;
                        }
                    }
                } while (l.size() > 0);
            }
        }
        return null;
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) throws Exception {
        return null;
    }

    @Override
    public AI clone() {
        return null;
    }

    @Override
    public List<ParameterSpecification> getParameters() {
        return null;
    }

    public String toString() {
        StringBuilder ret = new StringBuilder("PlayerActionGenerator:\n");
        for(Pair<Unit,List<AbstractAction>> choice:choices) {
            ret.append("  (").append(choice.m_a).append(",").append(choice.m_b.size()).append(")\n");
        }
        ret.append("currentChoice: ");
        for (int aCurrentChoice : currentChoice) {
            ret.append(aCurrentChoice).append(" ");
        }
        ret.append("\nactions generated so far: ").append(generated);
        return ret.toString();
    }

    public long getSize() {
        return size;
    }

    public List<Pair<Unit,List<AbstractAction>>> getChoices() {
        return choices;
    }

    void inform(HashMap<Unit, AbstractAction> abs) {

        for(Map.Entry<Unit, AbstractAction> ent : abs.entrySet())
        {
            Unit u = ent.getKey();
            AbstractAction aa = ent.getValue();
            aa.reset();

            actions.put(u,aa);
        }

    }
}
