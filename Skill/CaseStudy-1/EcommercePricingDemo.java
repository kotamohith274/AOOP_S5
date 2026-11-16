import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class EcommercePricingDemo {

    // 1. Value Object (Enum): Closed set of currencies with exact decimal precision
    public enum Currency {
        EUR(2), USD(2), JPY(0), KWD(3);

        private final int minorUnits;

        Currency(int minorUnits) {
            this.minorUnits = minorUnits;
        }

        public int minorUnits() {
            return minorUnits;
        }
    }

    // Custom domain exception for currency mismatches
    public static class CurrencyMismatchException extends RuntimeException {
        public CurrencyMismatchException(Currency expected, Currency actual) {
            super("Currency mismatch: cannot combine " + expected + " and " + actual);
        }
    }

    // 2. Value Object: Immutable, exact monetary amount in minor units
    public static final class Money {
        private final long minor;
        private final Currency currency;

        private Money(long minor, Currency currency) {
            if (minor < 0) {
                throw new IllegalArgumentException("Money amount cannot be negative: " + minor);
            }
            this.minor = minor;
            this.currency = Objects.requireNonNull(currency, "Currency cannot be null");
        }

        public static Money of(String decimal, Currency currency) {
            Objects.requireNonNull(decimal, "Amount string cannot be null");
            Objects.requireNonNull(currency, "Currency cannot be null");

            BigDecimal bd = new BigDecimal(decimal);
            if (bd.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Price cannot be negative: " + decimal);
            }
            if (bd.scale() > currency.minorUnits()) {
                throw new IllegalArgumentException(
                    "Excess precision for " + currency + ": max " + currency.minorUnits() + " decimals allowed"
                );
            }

            long minorUnits = bd.movePointRight(currency.minorUnits()).longValueExact();
            return new Money(minorUnits, currency);
        }

        public static Money zero(Currency currency) {
            return new Money(0L, currency);
        }

        public Money plus(Money other) {
            requireSameCurrency(other);
            return new Money(Math.addExact(this.minor, other.minor), this.currency);
        }

        public Money times(Quantity quantity) {
            Objects.requireNonNull(quantity, "Quantity cannot be null");
            return new Money(Math.multiplyExact(this.minor, quantity.value()), this.currency);
        }

        public Money minus(Percentage discount) {
            Objects.requireNonNull(discount, "Percentage cannot be null");
            // Calculate discount = (minor * basisPoints) / 10,000 using HALF_EVEN rounding
            BigDecimal discountAmount = BigDecimal.valueOf(this.minor)
                    .multiply(BigDecimal.valueOf(discount.basisPoints()))
                    .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_EVEN);

            long discountedMinor = Math.max(0L, this.minor - discountAmount.longValueExact());
            return new Money(discountedMinor, this.currency);
        }

        private void requireSameCurrency(Money other) {
            Objects.requireNonNull(other, "Other money cannot be null");
            if (this.currency != other.currency) {
                throw new CurrencyMismatchException(this.currency, other.currency);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Money)) return false;
            Money money = (Money) o;
            return minor == money.minor && currency == money.currency;
        }

        @Override
        public int hashCode() {
            return Objects.hash(minor, currency);
        }

        @Override
        public String toString() {
            if (currency.minorUnits() == 0) {
                return currency + " " + minor;
            }
            BigDecimal display = BigDecimal.valueOf(minor).movePointLeft(currency.minorUnits());
            return currency + " " + display.toPlainString();
        }
    }

    // 3. Value Object: Validated percentage stored in basis points (0 to 10,000)
    public static final class Percentage {
        private final int basisPoints;

        private Percentage(int basisPoints) {
            this.basisPoints = basisPoints;
        }

        public static Percentage ofBasisPoints(int bp) {
            if (bp < 0 || bp > 10_000) {
                throw new IllegalArgumentException("Percentage must be between 0 and 10,000 basis points (0% - 100%)");
            }
            return new Percentage(bp);
        }

        public static Percentage ofPercent(double percent) {
            int bp = (int) Math.round(percent * 100);
            return ofBasisPoints(bp);
        }

        public int basisPoints() {
            return basisPoints;
        }

        public Money applyTo(Money amount) {
            Objects.requireNonNull(amount, "Money cannot be null");
            BigDecimal discount = BigDecimal.valueOf(amount.minor)
                    .multiply(BigDecimal.valueOf(this.basisPoints))
                    .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_EVEN);
            return new Money(discount.longValueExact(), amount.currency);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Percentage)) return false;
            Percentage that = (Percentage) o;
            return basisPoints == that.basisPoints;
        }

        @Override
        public int hashCode() {
            return Objects.hash(basisPoints);
        }

        @Override
        public String toString() {
            return (basisPoints / 100.0) + "%";
        }
    }

    // 4. Value Object: Positive integer quantity (>= 1)
    public static final class Quantity {
        private final int value;

        private Quantity(int value) {
            this.value = value;
        }

        public static Quantity of(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("Quantity must be at least 1, got: " + value);
            }
            return new Quantity(value);
        }

        public Quantity plus(Quantity other) {
            return new Quantity(Math.addExact(this.value, other.value));
        }

        public int value() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Quantity)) return false;
            Quantity quantity = (Quantity) o;
            return value == quantity.value;
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    // 5. Entity: Identified by SKU; price and discount can change
    public static class Product {
        private final String sku;
        private String name;
        private Money basePrice;
        private Percentage discount;

        public Product(String sku, String name, Money basePrice, Percentage discount) {
            this.sku = Objects.requireNonNull(sku, "SKU cannot be null");
            this.name = Objects.requireNonNull(name, "Name cannot be null");
            this.basePrice = Objects.requireNonNull(basePrice, "Base price cannot be null");
            this.discount = Objects.requireNonNull(discount, "Discount cannot be null");
        }

        public Money effectivePrice() {
            return basePrice.minus(discount);
        }

        public String sku() {
            return sku;
        }

        public String name() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Product)) return false;
            Product product = (Product) o;
            return sku.equals(product.sku); // Entity equality is based strictly on SKU
        }

        @Override
        public int hashCode() {
            return Objects.hash(sku);
        }
    }

    // 6. CartLine (Value inside Cart aggregate)
    public static final class CartLine {
        private final Product product;
        private final Quantity quantity;

        public CartLine(Product product, Quantity quantity) {
            this.product = Objects.requireNonNull(product);
            this.quantity = Objects.requireNonNull(quantity);
        }

        public Money lineTotal() {
            return product.effectivePrice().times(quantity);
        }

        @Override
        public String toString() {
            return product.name() + " x" + quantity + " -> " + lineTotal();
        }
    }

    // 7. Entity / Aggregate Root: Identified by cartId
    public static class Cart {
        private final String cartId;
        private final Currency currency;
        private final List<CartLine> lines = new ArrayList<>();

        public Cart(String cartId, Currency currency) {
            this.cartId = Objects.requireNonNull(cartId);
            this.currency = Objects.requireNonNull(currency);
        }

        public void addItem(Product product, Quantity quantity) {
            if (product.effectivePrice().currency != this.currency) {
                throw new CurrencyMismatchException(this.currency, product.effectivePrice().currency);
            }
            lines.add(new CartLine(product, quantity));
        }

        public Money total() {
            Money sum = Money.zero(currency);
            for (CartLine line : lines) {
                sum = sum.plus(line.lineTotal());
            }
            return sum;
        }

        public List<CartLine> lines() {
            return Collections.unmodifiableList(lines);
        }
    }

    // Demonstration Runner
    public static void main(String[] args) {
        Product keyboard = new Product(
            "SKU-101",
            "Mechanical Keyboard",
            Money.of("120.00", Currency.USD),
            Percentage.ofPercent(15) // 15% discount -> USD 102.00
        );

        Product mouse = new Product(
            "SKU-102",
            "Wireless Mouse",
            Money.of("49.99", Currency.USD),
            Percentage.ofBasisPoints(0) // 0% discount -> USD 49.99
        );

        Cart cart = new Cart("CART-9001", Currency.USD);
        cart.addItem(keyboard, Quantity.of(2));
        cart.addItem(mouse, Quantity.of(1));

        System.out.println("Cart Lines:");
        for (CartLine line : cart.lines()) {
            System.out.println(" - " + line);
        }
        System.out.println("Cart Total: " + cart.total());

        // Demonstrating invariant protection:
        try {
            Product euroCable = new Product(
                "SKU-201", "USB-C Cable", Money.of("10.00", Currency.EUR), Percentage.ofPercent(0)
            );
            cart.addItem(euroCable, Quantity.of(1));
        } catch (CurrencyMismatchException e) {
            System.out.println("\nBlocked invalid state: " + e.getMessage());
        }
    }
}