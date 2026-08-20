package eu.client.utils.animations;

import eu.client.utils.system.MathUtils;

public class AnimateUtil {

    public static double animate(double current, double endPoint, double speed) {
        if (speed >= 1.0) {
            return endPoint;
        } else if (speed <= 0.0) {
            return current;
        } else {
            boolean shouldContinueAnimation = endPoint > current;
            double dif = Math.abs(endPoint - current);
            if (dif <= 0.001) {
                return endPoint;
            } else {
                double factor = dif * speed;
                return current + (shouldContinueAnimation ? factor : -factor);
            }
        }
    }

    public static float animate(float current, float endPoint, float speed) {
        if (speed >= 1.0F) {
            return endPoint;
        } else if (speed <= 0.0F) {
            return current;
        } else {
            boolean shouldContinueAnimation = endPoint > current;
            float dif = Math.abs(endPoint - current);
            if (dif <= 0.001F) {
                return endPoint;
            } else {
                float factor = dif * speed;
                return current + (shouldContinueAnimation ? factor : -factor);
            }
        }
    }
}
