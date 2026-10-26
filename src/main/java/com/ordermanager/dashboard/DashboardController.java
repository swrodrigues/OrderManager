package com.ordermanager.dashboard;

import com.ordermanager.order.OrderStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Controller
public class DashboardController {

    // chart geometry (SVG user units)
    static final int BAR_WIDTH = 56;
    static final int BAR_STEP = 80;
    static final int MAX_BAR_HEIGHT = 160;
    static final int BASELINE = 190;

    private final KpiQueryService kpis;

    public DashboardController(KpiQueryService kpis) {
        this.kpis = kpis;
    }

    /** One bar of the orders-by-status chart; {@code path} is empty for a zero count. */
    public record Bar(String label, long count, int x, int labelX, int valueY, String path, String tooltip) {
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        Kpis k = kpis.kpis();
        model.addAttribute("kpis", k);
        model.addAttribute("open", k.countByStatus().get(OrderStatus.NEW) + k.countByStatus().get(OrderStatus.ALLOCATED)
                + k.countByStatus().get(OrderStatus.PICKED) + k.countByStatus().get(OrderStatus.PACKED));
        model.addAttribute("bars", bars(k));
        model.addAttribute("chartWidth", 20 + OrderStatus.values().length * BAR_STEP);
        model.addAttribute("baseline", BASELINE);
        return "dashboard/index";
    }

    static List<Bar> bars(Kpis k) {
        long max = Math.max(1, Collections.max(k.countByStatus().values()));
        List<Bar> bars = new ArrayList<>();
        int i = 0;
        for (OrderStatus s : OrderStatus.values()) {
            long count = k.countByStatus().get(s);
            int h = (int) Math.round((double) count / max * MAX_BAR_HEIGHT);
            int x = 20 + i * BAR_STEP;
            bars.add(new Bar(s.name(), count, x, x + BAR_WIDTH / 2, BASELINE - h - 6, roundedTop(x, h),
                    s.name() + ": " + count + (count == 1 ? " order" : " orders")));
            i++;
        }
        return bars;
    }

    /** Bar anchored to the baseline with a 4px rounded data end. */
    private static String roundedTop(int x, int h) {
        if (h <= 0) {
            return "";
        }
        int r = Math.min(4, h);
        int top = BASELINE - h;
        int right = x + BAR_WIDTH;
        return "M" + x + "," + BASELINE + " V" + (top + r) + " Q" + x + "," + top + " " + (x + r) + "," + top
                + " H" + (right - r) + " Q" + right + "," + top + " " + right + "," + (top + r)
                + " V" + BASELINE + " Z";
    }
}
