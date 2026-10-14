package com.abada.engine.core.model;

import java.io.Serializable;

/**
 * An APL {@code for-each} node (E21): the token waits while a governed child of the node's process runs once per
 * item of a list, at most {@code maxParallel} at a time. The node is also registered as a
 * {@link CallProcessMeta} (so its child is pinned, depth-checked and cancelled like any call); this record holds
 * what only a loop needs.
 *
 * @param itemsExpression evaluated on the parent's variables to the list to go over
 * @param itemInput the child variable that receives the current item
 * @param maxItems the most items the node accepts; the engine's limit may be lower
 * @param maxParallel children running at once; the engine's limit may be lower
 * @param resultsVariable the parent variable that receives the list of results
 */
public record ForEachMeta(String id, String itemsExpression, String itemInput, int maxItems, int maxParallel,
        String resultsVariable) implements Serializable {
    /** The placeholder expression kept for the item input in the node's call, which is never evaluated. */
    public static final String ITEM_PLACEHOLDER = "item";
}
