package com.henry.dayflow;

import java.util.ArrayList;
import java.util.List;

final class DayGoal {
    String day;
    int focusTargetMinutes;
    int distractionLimitMinutes;
    boolean skipped;
    final List<DayGoalCategory> focusCategories = new ArrayList<>();
    final List<DayGoalCategory> distractionCategories = new ArrayList<>();
}
