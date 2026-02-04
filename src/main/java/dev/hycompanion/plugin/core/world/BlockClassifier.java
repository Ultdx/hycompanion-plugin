package dev.hycompanion.plugin.core.world;

import java.util.*;

/**
 * Classifies blocks by material type based on their IDs and display names.
 * Uses keyword extraction and pattern matching since Hytale block tags are often empty.
 * 
 * This enables the LLM to understand that "Cedar Trunk" is "wood" even if
 * the block's tags don't explicitly say "wood".
 * 
 * @author Hycompanion Team
 */
public class BlockClassifier {

    // Material type → keywords that indicate that material
    // Order matters: more specific types should come before generic ones
    private static final Map<String, List<String>> MATERIAL_KEYWORDS = new LinkedHashMap<>();
    
    static {
        // Wood and tree materials
        MATERIAL_KEYWORDS.put("wood", Arrays.asList(
            "wood", "trunk", "log", "plank", "timber", "lumber",
            "cedar", "oak", "birch", "spruce", "pine", "fir", "ash", "elm",
            "willow", "maple", "mahogany", "ebony", "teak", "acacia", "bamboo"
        ));
        
        // Stone and rock materials
        MATERIAL_KEYWORDS.put("stone", Arrays.asList(
            "stone", "rock", "cobble", "cobblestone", "granite", "marble", 
            "slate", "basalt", "obsidian", "sandstone", "limestone", 
            "quartz", "diorite", "andesite", "pumice", "chalk", "shale"
        ));
        
        // Ores and minerals
        MATERIAL_KEYWORDS.put("ore", Arrays.asList(
            "ore", "iron", "gold", "copper", "coal", "diamond", "emerald",
            "ruby", "sapphire", "amethyst", "topaz", "coal", "tin", "lead",
            "silver", "mithril", "adamantite", "crystal", "gem"
        ));
        
        // Metals (processed, not ore)
        MATERIAL_KEYWORDS.put("metal", Arrays.asList(
            "metal", "steel", "bronze", "brass", "iron_bar", "gold_bar",
            "copper_bar", "silver_bar", "plate", "sheet"
        ));
        
        // Soil and earth materials
        MATERIAL_KEYWORDS.put("soil", Arrays.asList(
            "dirt", "soil", "mud", "clay", "sand", "gravel", "silt", 
            "peat", "loam", "earth", "dust"
        ));
        
        // Plants and organic materials
        MATERIAL_KEYWORDS.put("plant", Arrays.asList(
            "plant", "crop", "flower", "leaf", "leaves", "sapling", "seed",
            "grass", "fern", "vine", "moss", "lichen", "bush", "shrub",
            "hay", "straw", "reed", "cane", "root", "bulb", "petal"
        ));
        
        // Fungi
        MATERIAL_KEYWORDS.put("fungus", Arrays.asList(
            "mushroom", "fungus", "fungi", "spore", "mycelium", "mold", "mould"
        ));
        
        // Fluids
        MATERIAL_KEYWORDS.put("fluid", Arrays.asList(
            "water", "lava", "fluid", "liquid", "flow", "stream", "river",
            "ocean", "lake", "puddle", "source"
        ));
        
        // Glass and transparent materials
        MATERIAL_KEYWORDS.put("glass", Arrays.asList(
            "glass", "window", "pane", "crystal_clear", "transparent"
        ));
        
        // Bricks and masonry
        MATERIAL_KEYWORDS.put("brick", Arrays.asList(
            "brick", "masonry", "tile", "ceramic", "porcelain", "clay_brick"
        ));
        
        // Ice and snow
        MATERIAL_KEYWORDS.put("ice", Arrays.asList(
            "ice", "snow", "frost", "frozen", "permafrost", "glacier"
        ));
        
        // Fabric and soft materials
        MATERIAL_KEYWORDS.put("fabric", Arrays.asList(
            "wool", "cloth", "fabric", "cotton", "silk", "linen", "canvas",
            "carpet", "rug", "tapestry", "curtain", "banner"
        ));
        
        // Construction/structural (generic building materials)
        MATERIAL_KEYWORDS.put("structural", Arrays.asList(
            "concrete", "cement", "plaster", "drywall", "roofing", "shingle",
            "beam", "pillar", "column", "support", "frame", "scaffold"
        ));
    }

    /**
     * Classifies a block based on its ID and display name.
     * 
     * @param blockId       The block's unique ID
     * @param displayName   The human-readable display name
     * @return BlockInfo with extracted material types and keywords
     */
    public static BlockInfo classify(String blockId, String displayName) {
        return classify(blockId, displayName, null, null);
    }
    
    /**
     * Classifies a block based on its ID, display name, and actual Hytale tags.
     * 
     * @param blockId       The block's unique ID (e.g., "Cloth_Block_Wool_Black")
     * @param displayName   The human-readable display name (e.g., "Black Cloth")
     * @param tags          The raw tags from Hytale block data (e.g., {"Type": ["Cloth"]})
     * @param categories    The block categories (e.g., ["Blocks.Rocks"])
     * @return BlockInfo with extracted material types and keywords
     */
    public static BlockInfo classify(String blockId, String displayName, 
            Map<String, String[]> tags, List<String> categories) {
        String normalizedId = normalize(blockId);
        String normalizedName = normalize(displayName);
        String combined = normalizedId + " " + normalizedName;
        
        // Extract keywords from ID and name
        Set<String> keywords = extractKeywords(combined);
        
        // Also extract from Hytale tags if available (e.g., "Cloth", "Ore", "Gold")
        if (tags != null) {
            for (Map.Entry<String, String[]> tagEntry : tags.entrySet()) {
                String tagKey = tagEntry.getKey(); // e.g., "Type", "Family"
                String[] tagValues = tagEntry.getValue(); // e.g., ["Cloth"], ["Ore"]
                
                for (String tagValue : tagValues) {
                    if (tagValue != null) {
                        keywords.add(normalize(tagValue));
                        // Also add the key=value combination
                        keywords.add(normalize(tagKey + "=" + tagValue));
                    }
                }
            }
        }
        
        // Determine material types from ID/name matching
        List<String> materialTypes = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : MATERIAL_KEYWORDS.entrySet()) {
            String material = entry.getKey();
            List<String> indicators = entry.getValue();
            
            for (String indicator : indicators) {
                if (combined.contains(indicator)) {
                    if (!materialTypes.contains(material)) {
                        materialTypes.add(material);
                    }
                    keywords.add(indicator);
                    break; // Found a match for this material, move to next
                }
            }
        }
        
        // Also infer material types from Hytale tags
        // e.g., if Type=["Cloth"] -> material type is "fabric"
        // e.g., if Type=["Ore"] -> material type is "ore"
        if (tags != null) {
            String[] typeTags = tags.get("Type");
            if (typeTags != null) {
                for (String typeTag : typeTags) {
                    String normalizedTag = normalize(typeTag);
                    switch (normalizedTag) {
                        case "cloth":
                        case "wool":
                            if (!materialTypes.contains("fabric")) materialTypes.add("fabric");
                            break;
                        case "ore":
                            if (!materialTypes.contains("ore")) materialTypes.add("ore");
                            break;
                        case "plant":
                        case "crop":
                            if (!materialTypes.contains("plant")) materialTypes.add("plant");
                            break;
                        case "stone":
                        case "rock":
                            if (!materialTypes.contains("stone")) materialTypes.add("stone");
                            break;
                        case "wood":
                        case "log":
                            if (!materialTypes.contains("wood")) materialTypes.add("wood");
                            break;
                        case "metal":
                            if (!materialTypes.contains("metal")) materialTypes.add("metal");
                            break;
                        case "glass":
                            if (!materialTypes.contains("glass")) materialTypes.add("glass");
                            break;
                        case "ice":
                            if (!materialTypes.contains("ice")) materialTypes.add("ice");
                            break;
                    }
                    keywords.add(normalizedTag);
                }
            }
            
            // Handle Family tags for more specific classification
            String[] familyTags = tags.get("Family");
            if (familyTags != null) {
                for (String familyTag : familyTags) {
                    keywords.add(normalize(familyTag));
                }
            }
        }
        
        // If no material type matched, mark as "misc"
        if (materialTypes.isEmpty()) {
            materialTypes.add("misc");
        }
        
        return new BlockInfo(
            blockId,
            displayName,
            Collections.unmodifiableList(materialTypes),
            Collections.unmodifiableList(new ArrayList<>(keywords)),
            categories != null ? Collections.unmodifiableList(categories) : Collections.emptyList()
        );
    }
    
    /**
     * Quick classification - just returns material types without full enrichment.
     * Useful for filtering.
     */
    public static List<String> getMaterialTypes(String blockId, String displayName) {
        String combined = normalize(blockId) + " " + normalize(displayName);
        List<String> types = new ArrayList<>();
        
        for (Map.Entry<String, List<String>> entry : MATERIAL_KEYWORDS.entrySet()) {
            for (String indicator : entry.getValue()) {
                if (combined.contains(indicator)) {
                    types.add(entry.getKey());
                    break;
                }
            }
        }
        
        return types.isEmpty() ? List.of("misc") : types;
    }
    
    /**
     * Checks if a block matches a specific material type.
     */
    public static boolean isMaterialType(String blockId, String displayName, String materialType) {
        return getMaterialTypes(blockId, displayName).contains(materialType.toLowerCase());
    }
    
    /**
     * Normalize a string for matching: lowercase, remove underscores/dashes.
     */
    private static String normalize(String input) {
        if (input == null) return "";
        return input.toLowerCase()
                   .replace("_", " ")
                   .replace("-", " ");
    }
    
    /**
     * Extract individual keywords from a string.
     */
    private static Set<String> extractKeywords(String normalized) {
        Set<String> keywords = new HashSet<>();
        String[] parts = normalized.split("\\s+");
        for (String part : parts) {
            if (part.length() > 2) { // Skip very short tokens
                keywords.add(part);
            }
        }
        return keywords;
    }
    
    /**
     * Get all known material types.
     */
    public static List<String> getAllMaterialTypes() {
        return List.copyOf(MATERIAL_KEYWORDS.keySet());
    }
}
