/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ai.bestAI;

import ai.evaluation.EvaluationFunction;
import rts.GameState;
import rts.PhysicalGameState;
import rts.units.Unit;

/**
 *
 * @author santi
 */
public class BestEvaluationFunction extends EvaluationFunction {
    public static float RESOURCE = 10;
    public static float RESOURCE_IN_WORKER = 0;
    public static float UNIT_BONUS_MULTIPLIER = 20;
    public static float DISTANCE_TO_RESOURCE = 10;
    public static float DISTANCE_TO_BASE = 5;
    public static float DISTANCE_TO_ENEMY = -25;
    public static float BARRACKS_POINTS = -50;
    public static float BASE_POINTS = -50;

    
    public float evaluate(int maxplayer, int minplayer, GameState gs) {
//        System.out.println("SimpleEvaluationFunction: " + base_score(maxplayer,gs) + " - " + base_score(minplayer,gs));
        return base_score(maxplayer,gs);
    }

    /**
     * Method to value a given game state
     */
    public float base_score(int player, GameState gs) {
        PhysicalGameState pgs = gs.getPhysicalGameState();

        // Give points for having resources available.
        float score = gs.getPlayer(player).getResources() * RESOURCE;

        int no_bases = 0;
        int no_barracks = 0;

        for(Unit u : pgs.getUnits()) {
            if (u.getPlayer() == player) {
                // Give points for units carrying resources
                score += u.getResources() * RESOURCE_IN_WORKER;

                // Give points for number of units. Value healthy units more.
                score += UNIT_BONUS_MULTIPLIER * (u.getCost() * u.getHitPoints())/(float)u.getMaxHitPoints();

                // Give points for workers being close to resources.
                if (u.getType().canHarvest) {
                    Unit closestResource = null;
                    int closestDistance = 0;
                    for (Unit u2 : pgs.getUnits()) {
                        if (u2.getType().isResource) {
                            int d = Math.abs(u2.getX() - u.getX()) + Math.abs(u2.getY() - u.getY());
                            if (closestResource == null || d < closestDistance) {
                                closestResource = u2;
                                closestDistance = d;
                            }
                        }
                    }
                    score += closestDistance * DISTANCE_TO_RESOURCE;
                }

                // Give points for fighters being away from base and close to enemies.
                if (u.getType().canAttack) {
                    Unit closestBase = null;
                    int closestDistance = 0;
                    for (Unit u2 : pgs.getUnits()) {
                        if (u2.getType().isStockpile) {
                            int d = Math.abs(u2.getX() - u.getX()) + Math.abs(u2.getY() - u.getY());
                            if (closestBase == null || d < closestDistance) {
                                closestBase = u2;
                                closestDistance = d;
                            }
                        }
                    }

                    Unit closestEnemy = null;
                    int closestDistanceE = 0;
                    for(Unit u2:pgs.getUnits()) {
                        if (u2.getPlayer() >= 0 && u2.getPlayer() != player) {
                            int d = Math.abs(u2.getX() - u.getX()) + Math.abs(u2.getY() - u.getY());
                            if (closestEnemy == null || d < closestDistanceE) {
                                closestEnemy = u2;
                                closestDistanceE = d;
                            }
                        }
                    }

                    score += closestDistance * DISTANCE_TO_BASE;
                    score += closestDistanceE * DISTANCE_TO_ENEMY;
                }

                // Give points for the number of bases and number of barracks over 1
                if (u.getType().name.equals("Barracks")) {
                    no_barracks ++;
                    if (no_barracks > 1) {
                        score += BARRACKS_POINTS;
                    }
                }
                if (u.getType().name.equals("Base")) {
                    no_barracks ++;
                    if (no_barracks > 1) {
                        score += BASE_POINTS;
                    }
                }
            }
        }
        return score;
    }

    /**
     * Calculate upper bound of state values. Old upper bound.
     * TODO: update this.
     */
    public float upperBound(GameState gs) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        int free_resources = 0;
        int player_resources[] = {gs.getPlayer(0).getResources(),gs.getPlayer(1).getResources()};
        for(Unit u:pgs.getUnits()) {
            if (u.getPlayer()==-1) free_resources+=u.getResources();
            if (u.getPlayer()==0) {
                player_resources[0] += u.getResources();
                player_resources[0] += u.getCost();
            }
            if (u.getPlayer()==1) {
                player_resources[1] += u.getResources();
                player_resources[1] += u.getCost();
            }
        }
//        System.out.println(free_resources + " + [" + player_resources[0] + " , " + player_resources[1] + "]");
//        if (free_resources + player_resources[0] + player_resources[1]>62) {
//            System.out.println(gs);
//        }
        return (free_resources + Math.max(player_resources[0],player_resources[1]))*UNIT_BONUS_MULTIPLIER;
    }
}
