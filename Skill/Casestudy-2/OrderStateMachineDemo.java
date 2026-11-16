import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class OrderStateMachineDemo {

    // --- Domain Exceptions ---
    public static class DomainException extends RuntimeException {
        public DomainException(String message) {
            super(message);
        }
    }

    // --- Value Objects ---
    public enum Currency {
        USD(2), EUR(2);
        final int minorUnits;
        Currency(int minorUnits) { this.minorUnits = minorUnits; }
    }

    public record Money(long minor, Currency currency) {
        public Money {
            if (minor < 0) throw new IllegalArgumentException("Money cannot be negative");
            Objects.requireNonNull(currency);
        }
        public static Money of(String amount, Currency c) {
            long m = new BigDecimal(amount).movePointRight(c.minorUnits).longValueExact();
            return new Money(m, c);
        }
        public static Money zero(Currency c) { return new Money(0L, c); }
        public Money plus(Money o) {
            if (this.currency != o.currency) throw new DomainException("Currency mismatch");
            return new Money(Math.addExact(this.minor, o.minor), this.currency);
        }
        public Money times(Quantity q) {
            return new Money(Math.multiplyExact(this.minor, q.value()), this.currency);
        }
        @Override
        public String toString() {
            return currency + " " + BigDecimal.valueOf(minor).movePointLeft(currency.minorUnits).toPlainString();
        }
    }

    public record Quantity(int value) {
        public Quantity {
            if (value < 1) throw new IllegalArgumentException("Quantity must be >= 1");
        }
        public Quantity plus(Quantity o) {
            return new Quantity(Math.addExact(this.value, o.value));
        }
    }

    public record OrderId(String value) {
        public OrderId { Objects.requireNonNull(value); }
    }

    public record ProductId(String value) {
        public ProductId { Objects.requireNonNull(value); }
    }

    public record TrackingNumber(String value) {
        public TrackingNumber { Objects.requireNonNull(value); }
    }

    public record PaymentRef(String value) {
        public PaymentRef { Objects.requireNonNull(value); }
    }

    public record Shipment(TrackingNumber tracking, Instant shippedAt) {
        public Shipment {
            Objects.requireNonNull(tracking);
            Objects.requireNonNull(shippedAt);
        }
    }

    public record OrderLine(ProductId productId, Money unitPrice, Quantity quantity) {
        public OrderLine {
            Objects.requireNonNull(productId);
            Objects.requireNonNull(unitPrice);
            Objects.requireNonNull(quantity);
        }
        public OrderLine combine(OrderLine other) {
            if (!this.productId.equals(other.productId)) {
                throw new DomainException("Cannot combine lines with different products");
            }
            return new OrderLine(this.productId, this.unitPrice, this.quantity.plus(other.quantity));
        }
        public Money total() {
            return unitPrice.times(quantity);
        }
    }

    public enum Status {
        DRAFT, PLACED, PAID, SHIPPED, DELIVERED, CANCELLED
    }

    // --- Refined Model A: Single Entity With Guarded State Machine ---
    public static final class Order {
        private final OrderId id;
        private final Currency currency;
        private Status status = Status.DRAFT;
        private final Map<ProductId, OrderLine> lines = new LinkedHashMap<>();
        private Optional<Shipment> shipment = Optional.empty();
        private Optional<PaymentRef> paymentRef = Optional.empty();
        private Optional<Instant> deliveredAt = Optional.empty();
        private Optional<Instant> cancelledAt = Optional.empty();

        public Order(OrderId id, Currency currency) {
            this.id = Objects.requireNonNull(id);
            this.currency = Objects.requireNonNull(currency);
        }

        public void addLine(ProductId p, Money unitPrice, Quantity q) {
            requireStatus(Status.DRAFT);
            if (unitPrice.currency() != this.currency) {
                throw new DomainException("Line currency must match order currency");
            }
            lines.merge(p, new OrderLine(p, unitPrice, q), OrderLine::combine);
        }

        public void removeLine(ProductId p) {
            requireStatus(Status.DRAFT);
            lines.remove(p);
        }

        public void place() {
            requireStatus(Status.DRAFT);
            if (lines.isEmpty()) {
                throw new DomainException("Cannot place an empty order");
            }
            status = Status.PLACED;
        }

        public void pay(PaymentRef ref) {
            requireStatus(Status.PLACED);
            this.paymentRef = Optional.of(Objects.requireNonNull(ref));
            status = Status.PAID;
        }

        public void ship(TrackingNumber t, Instant at) {
            requireStatus(Status.PAID);
            this.shipment = Optional.of(new Shipment(t, at));
            status = Status.SHIPPED;
        }

        public void deliver(Instant at) {
            requireStatus(Status.SHIPPED);
            this.deliveredAt = Optional.of(Objects.requireNonNull(at));
            status = Status.DELIVERED;
        }

        public void cancel(Instant at) {
            if (status != Status.DRAFT && status != Status.PLACED && status != Status.PAID) {
                throw new DomainException("Cannot cancel order in status: " + status);
            }
            this.cancelledAt = Optional.of(Objects.requireNonNull(at));
            status = Status.CANCELLED;
        }

        public Money total() {
            return lines.values().stream()
                    .map(OrderLine::total)
                    .reduce(Money.zero(currency), Money::plus);
        }

        public List<OrderLine> lines() {
            return List.copyOf(lines.values());
        }

        public Status status() {
            return status;
        }

        public Optional<Shipment> shipment() {
            return shipment;
        }

        private void requireStatus(Status expected) {
            if (this.status != expected) {
                throw new DomainException("Expected status " + expected + " but was " + this.status);
            }
        }
    }

    // --- Demonstration Runner ---
    public static void main(String[] args) {
        Order order = new Order(new OrderId("ORD-1001"), Currency.USD);
        ProductId laptop = new ProductId("PROD-LAPTOP");
        ProductId mouse = new ProductId("PROD-MOUSE");

        // Adding same product merges quantity automatically
        order.addLine(laptop, Money.of("1000.00", Currency.USD), new Quantity(1));
        order.addLine(laptop, Money.of("1000.00", Currency.USD), new Quantity(1));
        order.addLine(mouse, Money.of("50.00", Currency.USD), new Quantity(2));

        System.out.println("Draft lines: " + order.lines().size() + " | Total: " + order.total());

        // Valid Lifecycle Transitions
        order.place();
        order.pay(new PaymentRef("PAY-9988"));
        order.ship(new TrackingNumber("TRK-5544"), Instant.now());
        System.out.println("Status: " + order.status() + " | Tracking: " + order.shipment().get().tracking().value());

        // Illegal Transition: Trying to cancel a shipped order
        try {
            order.cancel(Instant.now());
        } catch (DomainException e) {
            System.out.println("Blocked illegal cancel: " + e.getMessage());
        }

        // Illegal Transition: Trying to ship a cancelled order
        Order cancelledOrder = new Order(new OrderId("ORD-1002"), Currency.USD);
        cancelledOrder.addLine(mouse, Money.of("50.00", Currency.USD), new Quantity(1));
        cancelledOrder.cancel(Instant.now());
        try {
            cancelledOrder.ship(new TrackingNumber("TRK-0000"), Instant.now());
        } catch (DomainException e) {
            System.out.println("Blocked shipping cancelled order: " + e.getMessage());
        }
    }
}