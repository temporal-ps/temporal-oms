package com.acme.enablements.commerce.workflows;

import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceInventoryState;
import com.acme.proto.acme.enablements.domain.enablements.v1.HoldInventoryRequest;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;

import java.util.HashMap;
import java.util.Map;

public class CommerceInventoryImpl implements CommerceInventory {

    private CommerceInventoryState state;

    @WorkflowInit
    public CommerceInventoryImpl(CommerceInventoryState initialState) {
        this.state = initialState;
    }

    @Override
    public void execute(CommerceInventoryState initialState) {
        Workflow.await(() -> false);
    }

    @Override
    public void hold(HoldInventoryRequest request) {
        adjustStock(request, -1);
    }

    @Override
    public void deduct(HoldInventoryRequest request) {
        adjustStock(request, -1);
    }

    @Override
    public void release(HoldInventoryRequest request) {
        adjustStock(request, 1);
    }

    @Override
    public CommerceInventoryState getState() {
        return state;
    }

    private void adjustStock(HoldInventoryRequest request, int sign) {
        Map<String, Integer> stock = new HashMap<>(state.getStockByItemIdMap());
        if (sign < 0) {
            for (var item : request.getItemsList()) {
                int available = stock.getOrDefault(item.getItemId(), 0);
                if (available < item.getQuantity()) {
                    throw ApplicationFailure.newFailure(
                            "Insufficient stock for item " + item.getItemId() + ": requested " + item.getQuantity() + ", available " + available,
                            "InsufficientStock");
                }
            }
        }
        for (var item : request.getItemsList()) {
            stock.merge(item.getItemId(), sign * item.getQuantity(), Integer::sum);
        }
        state = state.toBuilder().clearStockByItemId().putAllStockByItemId(stock).build();
    }
}
