package com.ordermanager.admin;

import com.ordermanager.inventory.Sku;
import com.ordermanager.inventory.StockService;
import com.ordermanager.security.Role;
import com.ordermanager.security.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Users and stock. URL rule /admin/** plus @PreAuthorize on the services keep this ADMIN-only. */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserService users;
    private final StockService stock;

    public AdminController(UserService users, StockService stock) {
        this.users = users;
        this.stock = stock;
    }

    // ---- users ----

    @GetMapping("/users")
    public String users(Model model) {
        model.addAttribute("users", users.listUsers());
        model.addAttribute("roles", Role.values());
        return "admin/users";
    }

    @PostMapping("/users")
    public String createUser(@RequestParam String username, @RequestParam String password, @RequestParam Role role,
                             RedirectAttributes redirect, HttpServletResponse res, Model model) {
        try {
            users.createUser(username.trim(), password, role);
        } catch (IllegalArgumentException e) {
            return userError(e, res, model);
        }
        redirect.addFlashAttribute("flash", "User " + username.trim() + " created");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/enabled")
    public String setEnabled(@PathVariable long id, @RequestParam boolean enabled, Authentication auth,
                             RedirectAttributes redirect, HttpServletResponse res, Model model) {
        try {
            users.setEnabled(id, enabled, auth.getName());
        } catch (IllegalArgumentException e) {
            return userError(e, res, model);
        }
        redirect.addFlashAttribute("flash", enabled ? "User enabled" : "User disabled");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/password")
    public String resetPassword(@PathVariable long id, @RequestParam String password,
                                RedirectAttributes redirect, HttpServletResponse res, Model model) {
        try {
            users.resetPassword(id, password);
        } catch (IllegalArgumentException e) {
            return userError(e, res, model);
        }
        redirect.addFlashAttribute("flash", "Password reset; the account is unlocked");
        return "redirect:/admin/users";
    }

    private String userError(IllegalArgumentException e, HttpServletResponse res, Model model) {
        res.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        model.addAttribute("error", e.getMessage());
        return users(model);
    }

    // ---- SKUs and stock ----

    @GetMapping("/skus")
    public String skus(Model model) {
        model.addAttribute("skus", stock.listSkus());
        return "admin/skus";
    }

    @PostMapping("/skus")
    public String createSku(@RequestParam String code, @RequestParam String name, @RequestParam int onHand,
                            RedirectAttributes redirect, HttpServletResponse res, Model model) {
        try {
            Sku sku = stock.createSku(code.trim(), name, onHand);
            redirect.addFlashAttribute("flash", "SKU " + sku.getCode() + " created");
        } catch (IllegalArgumentException e) {
            return skuError(e, res, model);
        }
        return "redirect:/admin/skus";
    }

    @PostMapping("/skus/{id}/adjust")
    public String adjust(@PathVariable long id, @RequestParam int delta, @RequestParam String reason,
                         RedirectAttributes redirect, HttpServletResponse res, Model model) {
        try {
            Sku sku = stock.adjustOnHand(id, delta, reason);
            redirect.addFlashAttribute("flash", "Stock for " + sku.getCode() + " is now " + sku.getOnHand());
        } catch (IllegalArgumentException e) {
            return skuError(e, res, model);
        }
        return "redirect:/admin/skus";
    }

    private String skuError(IllegalArgumentException e, HttpServletResponse res, Model model) {
        res.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        model.addAttribute("error", e.getMessage());
        return skus(model);
    }
}
