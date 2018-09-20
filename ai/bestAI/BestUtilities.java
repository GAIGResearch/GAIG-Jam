package ai.bestAI;

import ai.abstraction.AbstractAction;
import ai.abstraction.Build;
import ai.abstraction.pathfinding.PathFinding;
import rts.*;
import rts.units.Unit;
import rts.units.UnitType;

import java.util.ArrayList;
import java.util.List;

public abstract class BestUtilities {

    /**
     * Method to decide position for new building.
     */
    public static int findBuildingPosition(List<Integer> reserved, int desiredX, int desiredY, Player p,
                                           PhysicalGameState pgs) {

        boolean[][] free = pgs.getAllFree();
        int x, y;

        /*
        System.out.println("-" + desiredX + "," + desiredY + "-------------------");
        for(int i = 0;i<free[0].length;i++) {
            for(int j = 0;j<free.length;j++) {
                System.out.print(free[j][i] + "\t");
            }
            System.out.println("");
        }
        */
        
        for (int l = 1; l < Math.max(pgs.getHeight(), pgs.getWidth()); l++) {
            for (int side = 0; side < 4; side++) {
                switch (side) {
                    case 0://up
                        y = desiredY - l;
                        if (y < 0) {
                            continue;
                        }
                        for (int dx = -l; dx <= l; dx++) {
                            x = desiredX + dx;
                            if (x < 0 || x >= pgs.getWidth()) {
                                continue;
                            }
                            int pos = x + y * pgs.getWidth();
                            if (!reserved.contains(pos) && free[x][y]) {
                                return pos;
                            }
                        }
                        break;
                    case 1://right
                        x = desiredX + l;
                        if (x >= pgs.getWidth()) {
                            continue;
                        }
                        for (int dy = -l; dy <= l; dy++) {
                            y = desiredY + dy;
                            if (y < 0 || y >= pgs.getHeight()) {
                                continue;
                            }
                            int pos = x + y * pgs.getWidth();
                            if (!reserved.contains(pos) && free[x][y]) {
                                return pos;
                            }
                        }
                        break;
                    case 2://down
                        y = desiredY + l;
                        if (y >= pgs.getHeight()) {
                            continue;
                        }
                        for (int dx = -l; dx <= l; dx++) {
                            x = desiredX + dx;
                            if (x < 0 || x >= pgs.getWidth()) {
                                continue;
                            }
                            int pos = x + y * pgs.getWidth();
                            if (!reserved.contains(pos) && free[x][y]) {
                                return pos;
                            }
                        }
                        break;
                    case 3://left
                        x = desiredX - l;
                        if (x < 0) {
                            continue;
                        }
                        for (int dy = -l; dy <= l; dy++) {
                            y = desiredY + dy;
                            if (y < 0 || y >= pgs.getHeight()) {
                                continue;
                            }
                            int pos = x + y * pgs.getWidth();
                            if (!reserved.contains(pos) && free[x][y]) {
                                return pos;
                            }
                        }
                        break;
                }
            }
        }
        return -1;
    }

    /**
     * Method to build a building
     */
    static void buildBuilding(PlayerAction pa, int player, GameState gs, Unit u, List<AbstractAction> l,
                               ArrayList<Unit> buildingList, UnitType buildingType, List<Integer> reservedPositions,
                               PathFinding a_pf) {
        Player p = gs.getPlayer(player);
        PhysicalGameState pgs = gs.getPhysicalGameState();
        if (buildingList.size() == 0) {
            // Check unit position. Build away from resources and other buildings.
            for (Unit u2:gs.getUnits()) {
                if (u2.getType().isResource || u2.getType().isStockpile) {
                    int d = Math.abs(u2.getX() - u.getX()) + Math.abs(u2.getY() - u.getY());
                    if (d < 3) {
                        return;
                    }
                }
            }
            if (p.getResources() >= buildingType.cost + pa.getResourceUsage().getResourcesUsed(player)) {
                int pos = findBuildingPosition(reservedPositions, u.getX(),u.getY(), p, pgs);
                reservedPositions.add(pos);
                l.add(new Build(u, buildingType, pos % pgs.getWidth(), pos / pgs.getWidth(), a_pf));
            }
        }
    }

    /**
     * Method to check if units kept in lists are still alive. Dead units are removed from memory
     */
    static void checkUnits(List<Unit> unitList, List<Unit> gameUnits) {
        List<Unit> deadList = new ArrayList<>();
        for (Unit w:unitList) {
            if (!gameUnits.contains(w)) {
                deadList.add(w);
            }
        }
        if (deadList.size() > 0) {
            unitList.removeAll(deadList);
            deadList.clear();
        }
    }

}
