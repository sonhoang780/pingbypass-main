package eu.client.utils.animations;

public class Smoother {
    private double smoothedValue;

    public double smooth(double original, double smoother, double partialTicks) {
        double alpha = 1.0 - Math.exp(-smoother * partialTicks);
        smoothedValue += (original - smoothedValue) * alpha;
        return smoothedValue;
    }

    public void clear() {
        smoothedValue = 0.0;
    }

    public double getSmoothedValue() {
        return smoothedValue;
    }
}
