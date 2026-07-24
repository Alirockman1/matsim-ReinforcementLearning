package org.matsim.withinday.utils;

import org.matsim.core.config.ReflectiveConfigGroup;

public class WithinDayConfigGroup extends ReflectiveConfigGroup {
    public static final String GROUP_NAME = "withinday";

    private String replanner = "default";
    private String observer = "default";

    public WithinDayConfigGroup() {
        super(GROUP_NAME);
    }

    @StringGetter("replanner")
    public String getReplanner() { return replanner; }

    @StringSetter("replanner")
    public void setReplanner(String replanner) { this.replanner = replanner; }

    @StringGetter("observer")
    public String getObserver() { return observer; }

    @StringSetter("observer")
    public void setObserver(String observer) { this.observer = observer; }
}