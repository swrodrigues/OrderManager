package com.ordermanager.order;

import com.ordermanager.audit.AuditService;
import com.ordermanager.common.GlobalErrorHandler;
import com.ordermanager.inventory.StockService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.SmartValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class OrderController {

    private final OrderService orders;
    private final OrderQueryService query;
    private final StockService stock;
    private final AuditService audit;
    private final SmartValidator validator;

    public OrderController(OrderService orders, OrderQueryService query, StockService stock, AuditService audit,
                           SmartValidator validator) {
        this.orders = orders;
        this.query = query;
        this.stock = stock;
        this.audit = audit;
        this.validator = validator;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/orders";
    }

    /** Full page normally; just the table rows for HTMX (filtering, "Load more", refresh after bulk allocate). */
    @GetMapping("/orders")
    public String list(@RequestParam(required = false) OrderStatus status,
                       @RequestParam(required = false) List<String> q,
                       @RequestParam(required = false) String after,
                       HttpServletRequest req, Model model) {
        // a repeated q (e.g. URL value + included form field) must not be joined into "a,b"
        String prefix = q == null ? null : q.stream().filter(s -> !s.isBlank()).findFirst().orElse(null);
        OrderFilter filter = new OrderFilter(status, prefix);
        model.addAttribute("filter", filter);
        model.addAttribute("page", query.page(filter, Cursor.decode(after), OrderQueryService.PAGE_SIZE));
        model.addAttribute("statuses", OrderStatus.values());
        return GlobalErrorHandler.isHtmx(req) ? "orders/rows :: rows" : "orders/list";
    }

    @GetMapping("/orders/{id}")
    public String detail(@PathVariable long id, Model model) {
        addDetail(orders.get(id), model);
        return "orders/detail";
    }

    @PostMapping("/orders/{id}/transition")
    public String transition(@PathVariable long id, @RequestParam OrderStatus to, @RequestParam long version,
                             @RequestParam(defaultValue = "row") String view, Model model) {
        CustomerOrder order = orders.transition(id, to, version);
        if ("detail".equals(view)) {
            addDetail(orders.get(id), model);
            return "orders/detail :: panel";
        }
        model.addAttribute("r", OrderRow.of(order));
        model.addAttribute("undoable", true); // the user just made this step, so they may undo it
        return "orders/rows :: single";
    }

    /** Reverts the last step after a mis-click; returns the row (or panel) plus a message for the flash area. */
    @PostMapping("/orders/{id}/undo")
    public String undo(@PathVariable long id, @RequestParam long version,
                       @RequestParam(defaultValue = "row") String view, Model model) {
        CustomerOrder order = orders.undo(id, version);
        audit.last("order", id).ifPresent(u ->
                model.addAttribute("message", "Undone: " + u.getFromStatus() + " → " + u.getToStatus()));
        if ("detail".equals(view)) {
            addDetail(orders.get(id), model);
            return "orders/detail :: panelWithMessage";
        }
        model.addAttribute("r", OrderRow.of(order));
        return "orders/rows :: undone";
    }

    @PostMapping("/orders/bulk-allocate")
    public String bulkAllocate(HttpServletResponse res, Model model) {
        BulkAllocateResult r = orders.bulkAllocate();
        res.setHeader("HX-Trigger", "orders-changed");
        model.addAttribute("kind", "ok");
        model.addAttribute("message", "Allocated " + r.allocated() + ", short of stock " + r.shortOfStock());
        return "fragments/alert :: flash";
    }

    @GetMapping("/orders/new")
    public String newOrder(Model model) {
        model.addAttribute("form", new NewOrderForm());
        model.addAttribute("skus", stock.listSkus());
        return "orders/new";
    }

    @PostMapping("/orders")
    public String create(@ModelAttribute("form") NewOrderForm form, BindingResult binding,
                         HttpServletResponse res, Model model) {
        form.dropEmptyLines();
        validator.validate(form, binding);
        if (!binding.hasErrors()) {
            try {
                CustomerOrder order = orders.create(form.toCommand());
                return "redirect:/orders/" + order.getId();
            } catch (IllegalArgumentException e) {
                binding.reject("order", e.getMessage());
            } catch (DataIntegrityViolationException e) {
                binding.rejectValue("externalRef", "duplicate",
                        "External reference " + form.getExternalRef() + " already exists");
            }
        }
        if (form.getLines().isEmpty()) {
            form.getLines().add(new NewOrderForm.LineForm());
        }
        res.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        model.addAttribute("skus", stock.listSkus());
        return "orders/new";
    }

    private void addDetail(CustomerOrder order, Model model) {
        model.addAttribute("order", order);
        model.addAttribute("next", OrderStateMachine.next(order.getStatus()));
        model.addAttribute("history", audit.history("order", order.getId()));
        model.addAttribute("undoable", orders.canUndo(order.getId()));
    }
}
