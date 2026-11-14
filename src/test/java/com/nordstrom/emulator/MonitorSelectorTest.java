package com.nordstrom.emulator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MonitorSelectorTest {

    @Test
    void mainMonitorOffersColorAndTheThreePhosphors() {
        MonitorSelector selector = new MonitorSelector(MonitorSelector.Monitor.MAIN, MonitorType.AMBER,
            type -> {}, type -> {});
        MonitorType[] expected = {MonitorType.COLOR, MonitorType.WHITE, MonitorType.GREEN, MonitorType.AMBER};
        assertEquals(expected.length, selector.comboBox().getItemCount());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], selector.comboBox().getItemAt(i));
        }
        assertEquals(MonitorType.AMBER, selector.comboBox().getSelectedItem());
    }

    @Test
    void eightyColumnMonitorOffersNoColor() {
        MonitorSelector selector = new MonitorSelector(MonitorSelector.Monitor.EIGHTY_COLUMN, MonitorType.GREEN,
            type -> {}, type -> {});
        MonitorType[] expected = {MonitorType.WHITE, MonitorType.GREEN, MonitorType.AMBER};
        assertEquals(expected.length, selector.comboBox().getItemCount());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], selector.comboBox().getItemAt(i));
        }
    }

    @Test
    void choosingATypeRemembersItAndRunsTheCallbackOnce() {
        List<MonitorType> changes = new ArrayList<>();
        List<MonitorType> remembered = new ArrayList<>();
        MonitorSelector selector = new MonitorSelector(MonitorSelector.Monitor.MAIN, MonitorType.COLOR,
            changes::add, remembered::add);
        selector.comboBox().setSelectedItem(MonitorType.GREEN);
        selector.comboBox().setSelectedItem(MonitorType.GREEN); // unchanged: no callback
        assertEquals(List.of(MonitorType.GREEN), changes);
        assertEquals(List.of(MonitorType.GREEN), remembered);
    }

    @Test
    void colorMonitorShowsTextWhite() {
        assertEquals(MonitorType.WHITE.lit(), MonitorType.COLOR.lit());
    }
}
