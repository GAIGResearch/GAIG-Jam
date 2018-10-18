package ai.evo_absmc;

import ai.abstraction.*;
import ai.abstraction.pathfinding.PathFinding;
import rts.GameState;
import rts.PhysicalGameState;
import rts.Player;
import rts.units.Unit;
import rts.units.UnitType;


public class AbstractActionBuilder {

    private PathFinding pf;
    private PlayerAbstractActionGenerator ai;
    private int resourcesUsed;
    private int player;

    private int MAX_WORKERS = 3;

    AbstractActionBuilder(PlayerAbstractActionGenerator ai, PathFinding pf, int pID)
    {
        this.pf = pf;
        this.ai = ai;
        this.player = pID;
        this.resourcesUsed = 0;
    }

    /**
     * Returns a train abstract action, to be executed by a builder. It creates an action of type 'type'
     * @param gs the current game state
     * @param builder the unit that trains the new unit
     * @param type the type of the new unit to be created
     * @return the Train abstract action; null if it couldn't be created.
     */
    Train trainAction(GameState gs, Unit builder, UnitType type)
    {
        Player p = gs.getPlayer(player);
        if (p.getResources() >= type.cost + resourcesUsed) {
            resourcesUsed += type.cost;

            if ((type == ai.workerType) && (ai.typeCount.containsKey(ai.workerType)) && (ai.typeCount.get(ai.workerType) >= MAX_WORKERS))
                return null;

            return new Train(builder, type);
        }
        return null;
    }

    /**
     * Builds a building of the type given
     * @param gs current game state
     * @param builder the unit that will build it
     * @param type the type of building that will be built
     * @return null if it couldn't build it
     */
    Build buildAction(GameState gs, Unit builder, UnitType type, int displacement)
    {
        Player p = gs.getPlayer(player);
        PhysicalGameState pgs = gs.getPhysicalGameState();

        // build a barracks:
        if (p.getResources() >= type.cost + resourcesUsed) {

            //buildIfNotAlreadyBuilding
            AbstractAction action = ai.getAbstractAction(builder);
            if (!(action instanceof Build)) {

                int pos = findPosition(p, pgs, displacement);
                if(pos != -1) {
                    Build b = new Build(builder, type, pos % pgs.getWidth(), pos / pgs.getWidth(), pf);
                    resourcesUsed += type.cost;
                    return b;
                }
            }
        }
        return null;
    }

    /**
     * Finds a position at 'displacement' cells away from the one of the player's bases. Iteratively tries to find
     * an empty spot along the way.
     * @param p Player whose base will be used as a starting point.
     * @param pgs Game state
     * @return The position found. -1 if no positions are free.
     */
    private int findPosition(Player p, PhysicalGameState pgs, int displacement)
    {
        int basePos = -1;
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() == p.getID() && u.getType().isStockpile) {
                basePos = u.getPosition(pgs);
            }
        }

        if(basePos == -1) return -1;

        boolean placed = false;
        boolean[][] free = pgs.getAllFree();
        while(!placed)
        {
            basePos += displacement;
            int x = basePos % pgs.getWidth();
            int y = basePos / pgs.getWidth();

            if(basePos > pgs.getWidth()*pgs.getHeight())
                break;

            if (free[x][y])
                placed = true;
        }

        if(placed)
            return basePos;
        return -1;
    }


    /**
     * Returns a harvest abstract action using a worker. The resource and stockpile associated with this
     * action are the closest ones to the worker.
     * @param gs the current game state
     * @param harvestWorker the worker that will harvest the resource
     * @return A HarvestSingle abstract action. Note that this object contains logic to harvest only one resource
     * unit from the resource pile.
     */
    HarvestSingle harvestAction(GameState gs, Unit harvestWorker)
    {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        Player p = gs.getPlayer(player);
        HarvestSingle h = null;

        if (harvestWorker.getType().canHarvest && harvestWorker.getPlayer() == player) {

            Unit closestBase = null;
            Unit closestResource = null;
            int closestDistance = 0;
            for(Unit u2:pgs.getUnits()) {
                if (u2.getType().isResource) {
                    int d = Math.abs(u2.getX() - harvestWorker.getX()) + Math.abs(u2.getY() - harvestWorker.getY());
                    if (closestResource==null || d<closestDistance) {
                        closestResource = u2;
                        closestDistance = d;
                    }
                }
            }
            closestDistance = 0;
            for(Unit u2:pgs.getUnits()) {
                if (u2.getType().isStockpile && u2.getPlayer()==p.getID()) {
                    int d = Math.abs(u2.getX() - harvestWorker.getX()) + Math.abs(u2.getY() - harvestWorker.getY());
                    if (closestBase==null || d<closestDistance) {
                        closestBase = u2;
                        closestDistance = d;
                    }
                }
            }
            if (closestResource!=null && closestBase!=null) {
                AbstractAction aa = ai.getAbstractAction(harvestWorker);
                if (aa instanceof HarvestSingle) {
                    HarvestSingle h_aa = (HarvestSingle)aa;
                    if (h_aa.getTarget() != closestResource || h_aa.getBase()!=closestBase)
                        h = new HarvestSingle(harvestWorker, closestResource, closestBase, pf);
                    //harvest(harvestWorker, closestResource, closestBase);
                } else {
                    //harvest(harvestWorker, closestResource, closestBase);
                    h = new HarvestSingle(harvestWorker, closestResource, closestBase, pf);
                }
            }
        }

        return h;
    }

    /**
     * Returns an attack abstract action to be carried out by a unit.
     * @param gs current game state
     * @param u unit to execute the abstract action
     * @return the attack abstract action or null if it wasn't possible to be created (i.e. there are no enemies).
     */
    public Attack meleeUnitBehavior(GameState gs, Unit u) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        Player p = gs.getPlayer(player);

        Unit closestEnemy = null;
        int closestDistance = 0;
        for (Unit u2 : pgs.getUnits()) {
            if (u2.getPlayer() >= 0 && u2.getPlayer() != p.getID()) {
                int d = Math.abs(u2.getX() - u.getX()) + Math.abs(u2.getY() - u.getY());
                if (closestEnemy == null || d < closestDistance) {
                    closestEnemy = u2;
                    closestDistance = d;
                }
            }
        }
        if (closestEnemy != null) {
            return new Attack(u, closestEnemy, pf);
        }
        return null;
    }

}
