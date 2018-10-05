package ai.bestAI;

import ai.abstraction.AbstractAction;
import ai.abstraction.pathfinding.PathFinding;
import rts.GameState;
import rts.ResourceUsage;
import rts.UnitAction;
import rts.units.Unit;
import util.XMLWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 *
 * @author santi
 */
public class BestHarvest extends AbstractAction {
    private Unit target;
    private Unit base;
    private PathFinding pf;

    BestHarvest(Unit u, Unit a_target, Unit a_base, PathFinding a_pf) {
        super(u);
        target = a_target;
        base = a_base;
        pf = a_pf;
    }
    
    
    public Unit getTarget() {
        return target;
    }
    
    
    public Unit getBase() {
        return base;
    }
    
    
    public boolean completed(GameState gs) {
        return !gs.getPhysicalGameState().getUnits().contains(target);
    }
    
    
    public boolean equals(Object o)
    {
        if (!(o instanceof BestHarvest)) return false;
        BestHarvest a = (BestHarvest)o;
        if (target.getID() != a.target.getID()) return false;
        if (base.getID() != a.base.getID()) return false;
        return pf.getClass() == a.pf.getClass();
    }
    

    public void toxml(XMLWriter w)
    {
        w.tagWithAttributes("Harvest","unitID=\""+getUnit().getID()+"\" target=\""+target.getID()
                +"\" base=\""+base.getID()+"\" pathfinding=\""+pf.getClass().getSimpleName()+"\"");
        w.tag("/Harvest");
    }

    /**
     * Execute harvest action. Unit will find closest resource if not carrying resource, pick one up, then return
     * to closest base and drop resource off.
     */
    public UnitAction execute(GameState gs, ResourceUsage ru) {
        if (getUnit().getResources()==0) {
            // go get resources:
//            System.out.println("findPathToAdjacentPosition from Harvest: (" + target.getX() + "," +
//                               target.getY() + ")");
            UnitAction move = pf.findPathToAdjacentPosition(getUnit(), target.getX()+target.getY()
                    * gs.getPhysicalGameState().getWidth(), gs, ru);
            if (move!=null) {
                if (gs.isUnitActionAllowed(getUnit(), move)) return move;
                else {
                    // Execute random move action
                    List<Integer> choices = new ArrayList<>();
                    choices.add(0);
                    choices.add(1);
                    choices.add(2);
                    choices.add(3);
                    Random r = new Random();
                    while (choices.size() > 0) {
                        int choice = r.nextInt(choices.size());
                        move = new UnitAction(UnitAction.TYPE_MOVE, choice);
                        if (gs.isUnitActionAllowed(getUnit(), move)) return move;
                        choices.remove(choice);
                    }
                }
                return null;
            }

            // harvest:
            if (target.getX() == getUnit().getX() &&
                target.getY() == getUnit().getY()-1) return new UnitAction(UnitAction.TYPE_HARVEST,UnitAction.DIRECTION_UP);
            if (target.getX() == getUnit().getX()+1 &&
                target.getY() == getUnit().getY()) return new UnitAction(UnitAction.TYPE_HARVEST,UnitAction.DIRECTION_RIGHT);
            if (target.getX() == getUnit().getX() &&
                target.getY() == getUnit().getY()+1) return new UnitAction(UnitAction.TYPE_HARVEST,UnitAction.DIRECTION_DOWN);
            if (target.getX() == getUnit().getX()-1 &&
                target.getY() == getUnit().getY()) return new UnitAction(UnitAction.TYPE_HARVEST,UnitAction.DIRECTION_LEFT);
        } else {
            // return resources:
//            System.out.println("findPathToAdjacentPosition from Return: (" + target.getX() + ","
//                               + target.getY() + ")");
            if (base != null) {
                UnitAction move = pf.findPathToAdjacentPosition(getUnit(), base.getX() + base.getY()
                        * gs.getPhysicalGameState().getWidth(), gs, ru);
                if (move != null) {
                    if (gs.isUnitActionAllowed(getUnit(), move)) return move;
                    else {
                        // Execute random move action
                        List<Integer> choices = new ArrayList<>();
                        choices.add(0);
                        choices.add(1);
                        choices.add(2);
                        choices.add(3);
                        Random r = new Random();
                        while (choices.size() > 0) {
                            int choice = r.nextInt(choices.size());
                            move = new UnitAction(UnitAction.TYPE_MOVE, choice);
                            if (gs.isUnitActionAllowed(getUnit(), move)) return move;
                            choices.remove(choice);
                        }
                    }
                    return null;
                }

            // harvest:
            if (base.getX() == getUnit().getX() &&
                base.getY() == getUnit().getY()-1) return new UnitAction(UnitAction.TYPE_RETURN,UnitAction.DIRECTION_UP);
            if (base.getX() == getUnit().getX()+1 &&
                base.getY() == getUnit().getY()) return new UnitAction(UnitAction.TYPE_RETURN,UnitAction.DIRECTION_RIGHT);
            if (base.getX() == getUnit().getX() &&
                base.getY() == getUnit().getY()+1) return new UnitAction(UnitAction.TYPE_RETURN,UnitAction.DIRECTION_DOWN);
            if (base.getX() == getUnit().getX()-1 &&
                base.getY() == getUnit().getY()) return new UnitAction(UnitAction.TYPE_RETURN,UnitAction.DIRECTION_LEFT);
            }
        }
        return null;
    }    
}
