package dev.hycompanion.plugin.api;

/**
 * Represents a 3D location in the game world
 * 
 * @param x     X coordinate
 * @param y     Y coordinate (height)
 * @param z     Z coordinate
 * @param world World/dimension name
 */
public record Location(
        double x,
        double y,
        double z,
        String world) {
    /**
     * Create location without world (uses default)
     */
    public static Location of(double x, double y, double z) {
        return new Location(x, y, z, "world");
    }

    /**
     * Create location with world
     */
    public static Location of(double x, double y, double z, String world) {
        return new Location(x, y, z, world);
    }

    /**
     * Parse location from string format "x,y,z" or "x,y,z,world"
     */
    public static Location parse(String str) {
        String[] parts = str.split(",");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid location format: " + str);
        }

        double x = Double.parseDouble(parts[0].trim());
        double y = Double.parseDouble(parts[1].trim());
        double z = Double.parseDouble(parts[2].trim());
        String world = parts.length > 3 ? parts[3].trim() : "world";

        return new Location(x, y, z, world);
    }

    /**
     * Calculate distance to another location
     */
    public double distanceTo(Location other) {
        if (other == null)
            return Double.MAX_VALUE;

        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Calculate 2D distance (ignoring Y)
     */
    public double distanceTo2D(Location other) {
        if (other == null)
            return Double.MAX_VALUE;

        double dx = this.x - other.x;
        double dz = this.z - other.z;

        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Add offset to location
     */
    public Location add(double dx, double dy, double dz) {
        return new Location(x + dx, y + dy, z + dz, world);
    }

    /**
     * Get block coordinates (floor)
     */
    public Location toBlockLocation() {
        return new Location(
                Math.floor(x),
                Math.floor(y),
                Math.floor(z),
                world);
    }

    /**
     * Convert to string format "x,y,z"
     */
    public String toCoordString() {
        return String.format("%.1f,%.1f,%.1f", x, y, z);
    }

    @Override
    public String toString() {
        return String.format("Location{x=%.2f, y=%.2f, z=%.2f, world='%s'}", x, y, z, world);
    }
}
