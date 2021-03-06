/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.recurly;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Minutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.ning.billing.recurly.model.Account;
import com.ning.billing.recurly.model.Accounts;
import com.ning.billing.recurly.model.AddOn;
import com.ning.billing.recurly.model.BillingInfo;
import com.ning.billing.recurly.model.Coupon;
import com.ning.billing.recurly.model.Coupons;
import com.ning.billing.recurly.model.Invoices;
import com.ning.billing.recurly.model.Plan;
import com.ning.billing.recurly.model.Subscription;
import com.ning.billing.recurly.model.SubscriptionUpdate;
import com.ning.billing.recurly.model.Subscriptions;
import com.ning.billing.recurly.model.Transaction;
import com.ning.billing.recurly.model.Transactions;

import static com.ning.billing.recurly.TestUtils.randomString;

public class TestRecurlyClient {

    public static final String RECURLY_PAGE_SIZE = "recurly.page.size";
    public static final String KILLBILL_PAYMENT_RECURLY_API_KEY = "killbill.payment.recurly.apiKey";
    public static final String KILLBILL_PAYMENT_RECURLY_DEFAULT_CURRENCY_KEY = "killbill.payment.recurly.currency";

    private static final Logger log = LoggerFactory.getLogger(TestRecurlyClient.class);

    // Default to USD for all tests, which is expected to be supported by Recurly by default
    // Multi Currency Support is an enterprise add-on
    private static final String CURRENCY = System.getProperty(KILLBILL_PAYMENT_RECURLY_DEFAULT_CURRENCY_KEY, "USD");

    private RecurlyClient recurlyClient;

    @BeforeMethod(groups = "integration")
    public void setUp() throws Exception {
        final String apiKey = System.getProperty(KILLBILL_PAYMENT_RECURLY_API_KEY);
        if (apiKey == null) {
            Assert.fail("You need to set your Recurly api key to run integration tests:" +
                        " -Dkillbill.payment.recurly.apiKey=...");
        }

        recurlyClient = new RecurlyClient(apiKey);
        recurlyClient.open();
    }

    @AfterMethod(groups = "integration")
    public void tearDown() throws Exception {
        recurlyClient.close();
    }

    @Test(groups = "integration")
    public void testGetPageSize() throws Exception {
        System.setProperty(RECURLY_PAGE_SIZE, "");
        Assert.assertEquals(new Integer("20"), RecurlyClient.getPageSize());
        System.setProperty(RECURLY_PAGE_SIZE, "350");
        Assert.assertEquals(new Integer("350"), RecurlyClient.getPageSize());
    }

    @Test(groups = "integration")
    public void testGetPageSizeGetParam() throws Exception {
        System.setProperty(RECURLY_PAGE_SIZE, "");
        Assert.assertEquals("per_page=20", RecurlyClient.getPageSizeGetParam());
        System.setProperty(RECURLY_PAGE_SIZE, "350");
        Assert.assertEquals("per_page=350", RecurlyClient.getPageSizeGetParam());
    }

    @Test(groups = "integration")
    public void testGetCoupons() throws Exception {
        final Coupons retrievedCoupons = recurlyClient.getCoupons();
        Assert.assertTrue(retrievedCoupons.size() >= 0);
    }

    @Test(groups = "integration")
    public void testPagination() throws Exception {
        System.setProperty(RECURLY_PAGE_SIZE, "1");

        final int minNumberOfAccounts = 5;
        for (int i = 0; i < minNumberOfAccounts; i++) {
            final Account accountData = TestUtils.createRandomAccount();
            recurlyClient.createAccount(accountData);
        }

        Accounts accounts = recurlyClient.getAccounts();
        Assert.assertNull(accounts.getPrevUrl());
        for (int i = 0; i < minNumberOfAccounts; i++) {
            // If the environment is used, we will have more than the ones we created
            Assert.assertTrue(accounts.getNbRecords() >= minNumberOfAccounts);
            Assert.assertEquals(accounts.size(), 1);
            if (i < minNumberOfAccounts - 1) {
                accounts = accounts.getNext();
            }
        }

        for (int i = minNumberOfAccounts - 1; i >= 0; i--) {
            Assert.assertTrue(accounts.getNbRecords() >= minNumberOfAccounts);
            Assert.assertEquals(accounts.size(), 1);
            accounts = accounts.getPrev();
        }
    }

    @Test(groups = "integration")
    public void testCreateAccountWithBadBillingInfo() throws Exception {
        final Account accountData = TestUtils.createRandomAccount();
        final BillingInfo billingInfoData = TestUtils.createRandomBillingInfo();
        // See http://docs.recurly.com/payment-gateways/test
        billingInfoData.setNumber("4000-0000-0000-0093");

        try {
            final Account account = recurlyClient.createAccount(accountData);
            billingInfoData.setAccount(account);

            recurlyClient.createOrUpdateBillingInfo(billingInfoData);
            Assert.fail();
        } catch (TransactionErrorException e) {
            Assert.assertEquals(e.getErrors().getTransactionError().getErrorCode(), "fraud_ip_address");
            Assert.assertEquals(e.getErrors().getTransactionError().getMerchantMessage(), "The payment gateway declined the transaction because it originated from an IP address known for fraudulent transactions.");
            Assert.assertEquals(e.getErrors().getTransactionError().getCustomerMessage(), "The transaction was declined. Please contact support.");
        }
    }

    @Test(groups = "integration")
    public void testCreateAccount() throws Exception {
        final Account accountData = TestUtils.createRandomAccount();
        final BillingInfo billingInfoData = TestUtils.createRandomBillingInfo();

        try {
            final DateTime creationDateTime = new DateTime(DateTimeZone.UTC);
            final Account account = recurlyClient.createAccount(accountData);

            // Test account creation
            Assert.assertNotNull(account);
            Assert.assertEquals(accountData.getAccountCode(), account.getAccountCode());
            Assert.assertEquals(accountData.getEmail(), account.getEmail());
            Assert.assertEquals(accountData.getFirstName(), account.getFirstName());
            Assert.assertEquals(accountData.getLastName(), account.getLastName());
            Assert.assertEquals(accountData.getUsername(), account.getUsername());
            Assert.assertEquals(accountData.getAcceptLanguage(), account.getAcceptLanguage());
            Assert.assertEquals(accountData.getCompanyName(), account.getCompanyName());
            // Verify we can serialize date times
            Assert.assertEquals(Minutes.minutesBetween(account.getCreatedAt(), creationDateTime).getMinutes(), 0);
            Assert.assertEquals(accountData.getAddress().getAddress1(), account.getAddress().getAddress1());
            Assert.assertEquals(accountData.getAddress().getAddress2(), account.getAddress().getAddress2());
            Assert.assertEquals(accountData.getAddress().getCity(), account.getAddress().getCity());
            Assert.assertEquals(accountData.getAddress().getState(), account.getAddress().getState());
            Assert.assertEquals(accountData.getAddress().getZip(), account.getAddress().getZip());
            Assert.assertEquals(accountData.getAddress().getCountry(), account.getAddress().getCountry());
            Assert.assertEquals(accountData.getAddress().getPhone(), account.getAddress().getPhone());

            log.info("Created account: {}", account.getAccountCode());

            // Test getting all
            final Accounts retrievedAccounts = recurlyClient.getAccounts();
            Assert.assertTrue(retrievedAccounts.size() > 0);

            // Test an account lookup
            final Account retrievedAccount = recurlyClient.getAccount(account.getAccountCode());
            Assert.assertEquals(retrievedAccount, account);

            // Create a BillingInfo
            billingInfoData.setAccount(account);

            final BillingInfo billingInfo = recurlyClient.createOrUpdateBillingInfo(billingInfoData);

            // Test BillingInfo creation
            Assert.assertNotNull(billingInfo);
            Assert.assertEquals(billingInfoData.getFirstName(), billingInfo.getFirstName());
            Assert.assertEquals(billingInfoData.getLastName(), billingInfo.getLastName());
            Assert.assertEquals(billingInfoData.getMonth(), billingInfo.getMonth());
            Assert.assertEquals(billingInfoData.getYear(), billingInfo.getYear());
            Assert.assertEquals(billingInfo.getCardType(), "Visa");
            log.info("Added billing info: {}", billingInfo.getCardType());

            // Test BillingInfo lookup
            final BillingInfo retrievedBillingInfo = recurlyClient.getBillingInfo(account.getAccountCode());
            Assert.assertEquals(retrievedBillingInfo, billingInfo);

        } finally {
            // Clean up
            recurlyClient.clearBillingInfo(accountData.getAccountCode());
            recurlyClient.closeAccount(accountData.getAccountCode());
        }
    }

    @Test(groups = "integration")
    public void testCreatePlan() throws Exception {
        final Plan planData = TestUtils.createRandomPlan();
        try {
            // Create a plan
            final DateTime creationDateTime = new DateTime(DateTimeZone.UTC);
            final Plan plan = recurlyClient.createPlan(planData);
            final Plan retPlan = recurlyClient.getPlan(plan.getPlanCode());

            // test creation of plan
            Assert.assertNotNull(plan);
            Assert.assertEquals(retPlan, plan);
            // Verify we can serialize date times
            Assert.assertEquals(Minutes.minutesBetween(plan.getCreatedAt(), creationDateTime).getMinutes(), 0);
            // Check that getting all the plans makes sense
            Assert.assertTrue(recurlyClient.getPlans().size() > 0);

        } finally {
            // Delete the plan
            recurlyClient.deletePlan(planData.getPlanCode());
            // Check that we deleted it
            final Plan retrievedPlan2 = recurlyClient.getPlan(planData.getPlanCode());
            if (null != retrievedPlan2) {
                Assert.fail("Failed to delete the Plan");
            }
        }
    }

    @Test(groups = "integration")
    public void testCreateSubscriptions() throws Exception {
        final Account accountData = TestUtils.createRandomAccount();
        final BillingInfo billingInfoData = TestUtils.createRandomBillingInfo();
        final Plan planData = TestUtils.createRandomPlan();

        try {
            // Create a user
            final Account account = recurlyClient.createAccount(accountData);

            // Create BillingInfo
            billingInfoData.setAccount(account);
            final BillingInfo billingInfo = recurlyClient.createOrUpdateBillingInfo(billingInfoData);
            Assert.assertNotNull(billingInfo);
            final BillingInfo retrievedBillingInfo = recurlyClient.getBillingInfo(account.getAccountCode());
            Assert.assertNotNull(retrievedBillingInfo);

            // Create a plan
            final Plan plan = recurlyClient.createPlan(planData);

            // Subscribe the user to the plan
            final Subscription subscriptionData = new Subscription();
            subscriptionData.setPlanCode(plan.getPlanCode());
            subscriptionData.setAccount(accountData);
            subscriptionData.setCurrency(CURRENCY);
            subscriptionData.setUnitAmountInCents(1242);
            final DateTime creationDateTime = new DateTime(DateTimeZone.UTC);
            final Subscription subscription = recurlyClient.createSubscription(subscriptionData);

            // Test subscription creation
            Assert.assertNotNull(subscription);
            Assert.assertEquals(subscription.getCurrency(), subscriptionData.getCurrency());
            if (null == subscriptionData.getQuantity()) {
                Assert.assertEquals(subscription.getQuantity(), new Integer(1));
            } else {
                Assert.assertEquals(subscription.getQuantity(), subscriptionData.getQuantity());
            }
            // Verify we can serialize date times
            Assert.assertEquals(Minutes.minutesBetween(subscription.getActivatedAt(), creationDateTime).getMinutes(),
                                0);
            log.info("Created subscription: {}", subscription.getUuid());

            // Test lookup for subscription
            final Subscription sub1 = recurlyClient.getSubscription(subscription.getUuid());
            Assert.assertNotNull(sub1);
            Assert.assertEquals(sub1, subscription);
            // Do a lookup for subs for given account
            final Subscriptions subs = recurlyClient.getAccountSubscriptions(accountData.getAccountCode());
            // Check that the newly created sub is in the list
            Subscription found = null;
            for (final Subscription s : subs) {
                if (s.getUuid().equals(subscription.getUuid())) {
                    found = s;
                    break;
                }
            }
            if (found == null) {
                Assert.fail("Could not locate the subscription in the subscriptions associated with the account");
            }

            // Verify the subscription information, including nested parameters
            // See https://github.com/killbilling/recurly-java-library/issues/4
            Assert.assertEquals(found.getAccount().getAccountCode(), accountData.getAccountCode());
            Assert.assertEquals(found.getAccount().getFirstName(), accountData.getFirstName());

            // Cancel a Subscription
            recurlyClient.cancelSubscription(subscription);
            final Subscription cancelledSubscription = recurlyClient.getSubscription(subscription.getUuid());
            Assert.assertEquals(cancelledSubscription.getState(), "canceled");

            recurlyClient.reactivateSubscription(subscription);
            final Subscription reactivatedSubscription = recurlyClient.getSubscription(subscription.getUuid());
            Assert.assertEquals(reactivatedSubscription.getState(), "active");

        } finally {
            // Clear up the BillingInfo
            recurlyClient.clearBillingInfo(accountData.getAccountCode());
            // Close the account
            recurlyClient.closeAccount(accountData.getAccountCode());
            // Delete the Plan
            recurlyClient.deletePlan(planData.getPlanCode());
        }
    }

    @Test(groups = "integration")
    public void testCreateAndQueryTransactions() throws Exception {
        final Account accountData = TestUtils.createRandomAccount();
        final BillingInfo billingInfoData = TestUtils.createRandomBillingInfo();
        final Plan planData = TestUtils.createRandomPlan();

        try {
            // Create a user
            final Account account = recurlyClient.createAccount(accountData);

            // Create BillingInfo
            billingInfoData.setAccount(account);
            final BillingInfo billingInfo = recurlyClient.createOrUpdateBillingInfo(billingInfoData);
            Assert.assertNotNull(billingInfo);
            final BillingInfo retrievedBillingInfo = recurlyClient.getBillingInfo(account.getAccountCode());
            Assert.assertNotNull(retrievedBillingInfo);

            // Create a plan
            final Plan plan = recurlyClient.createPlan(planData);

            // Subscribe the user to the plan
            final Subscription subscriptionData = new Subscription();
            subscriptionData.setPlanCode(plan.getPlanCode());
            subscriptionData.setAccount(accountData);
            subscriptionData.setUnitAmountInCents(150);
            subscriptionData.setCurrency(CURRENCY);
            recurlyClient.createSubscription(subscriptionData);

            // Create a transaction
            final Transaction t = new Transaction();
            accountData.setBillingInfo(billingInfoData);
            t.setAccount(accountData);
            t.setAmountInCents(15);
            t.setCurrency(CURRENCY);
            final Transaction createdT = recurlyClient.createTransaction(t);

            // Test that the transaction created correctly
            Assert.assertNotNull(createdT);
            // Can't test for account equality yet as the account is only a ref and doesn't get mapped.
            Assert.assertEquals(createdT.getAmountInCents(), t.getAmountInCents());
            Assert.assertEquals(createdT.getCurrency(), t.getCurrency());
            log.info("Created transaction: {}", createdT.getUuid());

            // Test lookup on the transaction via the users account
            final Transactions trans = recurlyClient.getAccountTransactions(account.getAccountCode());
            // 3 transactions: voided verification, $1.5 for the plan and the $0.15 transaction above
            Assert.assertEquals(trans.size(), 3);
            Transaction found = null;
            for (final Transaction _t : trans) {
                if (_t.getUuid().equals(createdT.getUuid())) {
                    found = _t;
                    break;
                }
            }
            if (found == null) {
                Assert.fail("Failed to locate the newly created transaction");
            }

            // Verify the transaction information, including nested parameters
            // See https://github.com/killbilling/recurly-java-library/issues/4
            Assert.assertEquals(found.getAccount().getAccountCode(), accountData.getAccountCode());
            Assert.assertEquals(found.getAccount().getFirstName(), accountData.getFirstName());
            Assert.assertEquals(found.getInvoice().getAccount().getAccountCode(), accountData.getAccountCode());
            Assert.assertEquals(found.getInvoice().getAccount().getFirstName(), accountData.getFirstName());
            Assert.assertEquals(found.getInvoice().getTotalInCents(), (Integer) 15);
            Assert.assertEquals(found.getInvoice().getCurrency(), CURRENCY);

            // Test Invoices retrieval
            final Invoices invoices = recurlyClient.getAccountInvoices(account.getAccountCode());
            // 2 Invoices are present (the first one is for the transaction, the second for the subscription)
            Assert.assertEquals(invoices.size(), 2, "Number of Invoices incorrect");
            Assert.assertEquals(invoices.get(0).getTotalInCents(), t.getAmountInCents(), "Amount in cents is not the same");
            Assert.assertEquals(invoices.get(1).getTotalInCents(), subscriptionData.getUnitAmountInCents(), "Amount in cents is not the same");
        } finally {
            // Clear up the BillingInfo
            recurlyClient.clearBillingInfo(accountData.getAccountCode());
            // Close the account
            recurlyClient.closeAccount(accountData.getAccountCode());
            // Delete the Plan
            recurlyClient.deletePlan(planData.getPlanCode());
        }
    }

    @Test(groups = "integration")
    public void testAddons() throws Exception {
        // Create a Plan
        final Plan planData = TestUtils.createRandomPlan();
        final AddOn addOn = TestUtils.createRandomAddOn();
        // add ons for plans must not have quantity
        addOn.setQuantity(null);

        try {
            // Create an AddOn
            final Plan plan = recurlyClient.createPlan(planData);
            AddOn addOnRecurly = recurlyClient.createPlanAddOn(plan.getPlanCode(), addOn);

            // Test the creation
            Assert.assertNotNull(addOnRecurly);
            Assert.assertEquals(addOnRecurly.getAddOnCode(), addOn.getAddOnCode());
            Assert.assertEquals(addOnRecurly.getName(), addOn.getName());
            Assert.assertEquals(addOnRecurly.getUnitAmountInCents(), addOn.getUnitAmountInCents());
            Assert.assertEquals(addOnRecurly.getQuantity(), addOn.getQuantity());

            // Query for an AddOn
            addOnRecurly = recurlyClient.getAddOn(plan.getPlanCode(), addOn.getAddOnCode());

            // Check the 2 are the same
            Assert.assertEquals(addOnRecurly.getAddOnCode(), addOn.getAddOnCode());
            Assert.assertEquals(addOnRecurly.getName(), addOn.getName());
            //Assert.assertEquals(addOnRecurly.getDefaultQuantity(), addOn.getDefaultQuantity());
            //Assert.assertEquals(addOnRecurly.getDisplayQuantityOnHostedPage(), addOn.getDisplayQuantityOnHostedPage());
            Assert.assertEquals(addOnRecurly.getUnitAmountInCents(), addOn.getUnitAmountInCents());
        } finally {
            // Delete an AddOn
            recurlyClient.deleteAddOn(planData.getPlanCode(), addOn.getAddOnCode());
            // Delete the plan
            recurlyClient.deletePlan(planData.getPlanCode());
        }
    }

    @Test(groups = "integration")
    public void testCreateCoupon() throws Exception {
        // Create the coupon
        final Coupon c = new Coupon();
        c.setName(randomString());
        c.setCouponCode(randomString());
        c.setDiscountType("percent");
        c.setDiscountPercent("10");

        // Save the coupon
        final Coupon coupon = recurlyClient.createCoupon(c);
        Assert.assertNotNull(coupon);

        Assert.assertEquals(coupon.getName(), c.getName());
        Assert.assertEquals(coupon.getCouponCode(), c.getCouponCode());
        Assert.assertEquals(coupon.getDiscountType(), c.getDiscountType());
        Assert.assertEquals(coupon.getDiscountPercent(), c.getDiscountPercent());
    }

    @Test(groups = "integration")
    public void testUpdateSubscriptions() throws Exception {
        final Account accountData = TestUtils.createRandomAccount();
        final BillingInfo billingInfoData = TestUtils.createRandomBillingInfo();
        final Plan planData = TestUtils.createRandomPlan();
        final Plan plan2Data = TestUtils.createRandomPlan(CURRENCY);

        try {
            // Create a user
            final Account account = recurlyClient.createAccount(accountData);

            // Create BillingInfo
            billingInfoData.setAccount(account);
            final BillingInfo billingInfo = recurlyClient.createOrUpdateBillingInfo(billingInfoData);
            Assert.assertNotNull(billingInfo);
            final BillingInfo retrievedBillingInfo = recurlyClient.getBillingInfo(account.getAccountCode());
            Assert.assertNotNull(retrievedBillingInfo);

            // Create a plan
            final Plan plan = recurlyClient.createPlan(planData);
            final Plan plan2 = recurlyClient.createPlan(plan2Data);
            log.info(plan2.toString());
            // Subscribe the user to the plan
            final Subscription subscriptionData = new Subscription();
            subscriptionData.setPlanCode(plan.getPlanCode());
            subscriptionData.setAccount(accountData);
            subscriptionData.setCurrency(CURRENCY);
            subscriptionData.setUnitAmountInCents(1242);
            final DateTime creationDateTime = new DateTime(DateTimeZone.UTC);
            final Subscription subscription = recurlyClient.createSubscription(subscriptionData);

            // Test subscription creation
            Assert.assertNotNull(subscription);
            log.info("Created subscription: {} with plan {}", subscription.getUuid(), subscription.getPlan().getPlanCode());

            final SubscriptionUpdate subscriptionUpdateData = new SubscriptionUpdate();
            subscriptionUpdateData.setTimeframe(SubscriptionUpdate.Timeframe.now);
            subscriptionUpdateData.setPlanCode(plan2.getPlanCode());
            final Subscription subscriptionUpdated = recurlyClient.updateSubscription(subscription.getUuid(), subscriptionUpdateData);

            Assert.assertNotNull(subscriptionUpdated);
            Assert.assertEquals(subscription.getUuid(), subscriptionUpdated.getUuid());
            Assert.assertNotEquals(subscription.getPlan(), subscriptionUpdated.getPlan());
            Assert.assertEquals(plan2.getPlanCode(), subscriptionUpdated.getPlan().getPlanCode());
            log.info("Updated subscription: {} with new plan {}", subscription.getUuid(), subscriptionUpdated.getPlan().getPlanCode());

        } finally {
            // Clear up the BillingInfo
            recurlyClient.clearBillingInfo(accountData.getAccountCode());
            // Close the account
            recurlyClient.closeAccount(accountData.getAccountCode());
            // Delete the Plans
            recurlyClient.deletePlan(planData.getPlanCode());
            recurlyClient.deletePlan(plan2Data.getPlanCode());
        }
    }
}
