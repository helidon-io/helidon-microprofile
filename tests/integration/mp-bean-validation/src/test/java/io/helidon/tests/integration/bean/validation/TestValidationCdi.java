/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.tests.integration.bean.validation;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;

import io.helidon.microprofile.testing.AddBean;
import io.helidon.microprofile.testing.junit5.HelidonTest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;

@HelidonTest
@AddBean(TestValidationCdi.AddressBook.class)
@AddBean(TestValidationCdi.DeliveryService.class)
class TestValidationCdi {
    @Inject
    private Validator validator;

    @Inject
    private ValidatorFactory validatorFactory;

    @Inject
    private DeliveryService deliveryService;

    @Test
    void testConstraintValidatorInjection() {
        assertThat(validator.validate(new Mailbox("known@example.com")), is(empty()));
        var violations = validator.validate(new Mailbox("other@example.com"));
        assertThat(violations, hasSize(1));
        assertThat(violations.iterator().next().getPropertyPath().toString(), is("address"));
    }

    @Test
    void testValidatorFactoryAndMessageInterpolation() {
        var violations = validatorFactory.getValidator().validate(new Order(1));
        assertThat(violations, hasSize(1));
        assertThat(violations.iterator().next().getMessage(), is("must be at least 2; got 1"));
    }

    @Test
    void testMethodValidation() {
        assertThat(deliveryService.deliver(new Mailbox("known@example.com")), is("known@example.com"));
        var exception = assertThrows(ConstraintViolationException.class,
                                     () -> deliveryService.deliver(new Mailbox("other@example.com")));
        assertThat(exception.getConstraintViolations(), hasSize(1));
        assertThrows(ConstraintViolationException.class, () -> deliveryService.deliver(null));
    }

    @Test
    void testContainerElementCascading() {
        var mailbox = new Mailbox("other@example.com");
        var violations = validator.validate(new MailingList(List.of(mailbox, mailbox)));
        assertThat(violations.stream().map(violation -> violation.getPropertyPath().toString()).toList(),
                   containsInAnyOrder("mailboxes[0].address", "mailboxes[1].address"));
    }

    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER, TYPE_USE})
    @Constraint(validatedBy = KnownAddressValidator.class)
    public @interface KnownAddress {
        String message() default "unknown address";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @ApplicationScoped
    public static class AddressBook {
        boolean contains(String address) {
            return "known@example.com".equals(address);
        }
    }

    public static class KnownAddressValidator implements ConstraintValidator<KnownAddress, String> {
        @Inject
        private AddressBook addressBook;

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return addressBook.contains(value);
        }
    }

    @ApplicationScoped
    public static class DeliveryService {
        public String deliver(@NotNull @Valid Mailbox mailbox) {
            return mailbox.address();
        }
    }

    record Mailbox(@KnownAddress String address) {
    }

    record MailingList(List<@Valid Mailbox> mailboxes) {
    }

    record Order(@Min(value = 2, message = "must be at least {value}; got ${validatedValue}") int quantity) {
    }
}
