/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.model;

import org.candlepin.common.config.Configuration;
import org.candlepin.common.exceptions.BadRequestException;
import org.candlepin.common.exceptions.NotFoundException;
import org.candlepin.common.paging.Page;
import org.candlepin.common.paging.PageRequest;
import org.candlepin.config.ConfigProperties;
import org.candlepin.resteasy.parameter.KeyValueParameter;
import org.candlepin.util.Util;

import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

import org.apache.commons.collections.CollectionUtils;
import org.hibernate.Criteria;
import org.hibernate.Hibernate;
import org.hibernate.Query;
import org.hibernate.ReplicationMode;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Disjunction;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.criterion.Subqueries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.persistence.LockModeType;

/**
 * ConsumerCurator
 */
public class ConsumerCurator extends AbstractHibernateCurator<Consumer> {

    @Inject private EntitlementCurator entitlementCurator;
    @Inject private ConsumerTypeCurator consumerTypeCurator;
    @Inject private DeletedConsumerCurator deletedConsumerCurator;
    @Inject private Configuration config;

    private static final int MAX_FACT_STR_LENGTH = 255;
    private static final int NAME_LENGTH = 250;
    private static final int MAX_IN_QUERY_LENGTH = 500;
    private static Logger log = LoggerFactory.getLogger(ConsumerCurator.class);

    public ConsumerCurator() {
        super(Consumer.class);
    }

    @Transactional
    @Override
    public Consumer create(Consumer entity) {
        entity.ensureUUID();
        if (entity.getFacts() != null) {
            entity.setFacts(filterAndVerifyFacts(entity));
        }

        return super.create(entity);
    }

    @Transactional
    public void delete(Consumer entity) {
        // save off the IDs before we delete
        DeletedConsumer dc = new DeletedConsumer(entity.getUuid(), entity.getOwner().getId(),
            entity.getOwner().getKey(), entity.getOwner().getDisplayName());

        super.delete(entity);

        DeletedConsumer existing = deletedConsumerCurator.findByConsumerUuid(dc.getConsumerUuid());
        if (existing != null) {
            // update the owner ID in case the same UUID was specified by two owners
            existing.setOwnerId(dc.getOwnerId());
            existing.setOwnerKey(dc.getOwnerKey());
            existing.setOwnerDisplayName(dc.getOwnerDisplayName());
            existing.setUpdated(new Date());
            deletedConsumerCurator.save(existing);
        }
        else {
            deletedConsumerCurator.create(dc);
        }
    }

    @Transactional
    public Consumer replicate(Consumer consumer) {
        for (Entitlement entitlement : consumer.getEntitlements()) {
            entitlement.setConsumer(consumer);
        }

        ConsumerType consumerType = consumerTypeCurator.lookupByLabel(consumer.getType().getLabel());
        consumer.setType(consumerType);

        IdentityCertificate idCert = consumer.getIdCert();
        this.currentSession().replicate(idCert.getSerial(), ReplicationMode.EXCEPTION);
        this.currentSession().replicate(idCert, ReplicationMode.EXCEPTION);

        this.currentSession().replicate(consumer, ReplicationMode.EXCEPTION);

        return consumer;
    }

    /**
     * Lookup consumer by its name
     *
     * @param name consumer name to find
     * @return Consumer whose name matches the given name, null otherwise.
     */
    @Transactional
    public Consumer findByName(Owner o, String name) {
        return (Consumer) createSecureCriteria()
            .add(Restrictions.eq("name", name))
            .add(Restrictions.eq("owner", o))
            .uniqueResult();
    }

    /**
     * Lookup consumer by its virt.uuid.
     *
     * In some cases the hypervisor will report UUIDs with uppercase, while the guest will
     * report lowercase. As such we do case insensitive comparison when looking these up.
     *
     * @param uuid consumer virt.uuid to find
     * @return Consumer whose name matches the given virt.uuid, null otherwise.
     */
    @Transactional
    public Consumer findByVirtUuid(String uuid, String ownerId) {
        Consumer result = null;
        List<String> possibleGuestIds = Util.getPossibleUuids(uuid);

        String sql = "select cp_consumer.id from cp_consumer " +
            "inner join cp_consumer_facts_lower " +
            "on cp_consumer.id = cp_consumer_facts_lower.cp_consumer_id " +
            "where cp_consumer_facts_lower.mapkey = 'virt.uuid' and " +
            "cp_consumer_facts_lower.element in (:guestids) " +
            "and cp_consumer.owner_id = :ownerid " +
            "order by cp_consumer.updated desc";

        Query q = currentSession().createSQLQuery(sql);
        q.setParameterList("guestids", possibleGuestIds);
        q.setParameter("ownerid", ownerId);
        List<String> options = q.list();

        if (options != null && options.size() != 0) {
            result = this.find(options.get(0));
        }

        return result;
    }

    /**
     * Lookup all consumers matching the given guest IDs.
     *
     * Maps guest ID to the most recent registered consumer that matches it.
     * Any guest ID not found will not return null.
     *
     * If multiple registered consumers report this guest ID (re-registraiton), only the
     * most recently updated will be returned.
     *
     * @param guestId
     *
     * @return VirtConsumerMap of guest ID to it's registered guest consumer, or null if
     * none exists.
     */
    @Transactional
    public VirtConsumerMap getGuestConsumersMap(Owner owner, Set<String> guestIds) {
        VirtConsumerMap guestConsumersMap = new VirtConsumerMap();

        if (guestIds.size() == 0) {
            return guestConsumersMap;
        }

        List<String> possibleGuestIds = Util.getPossibleUuids(guestIds.toArray(new String [guestIds.size()]));

        String sql = "select cp_consumer.uuid from cp_consumer " +
            "inner join cp_consumer_facts_lower " +
            "on cp_consumer.id = cp_consumer_facts_lower.cp_consumer_id " +
            "where cp_consumer_facts_lower.mapkey = 'virt.uuid' and " +
            "cp_consumer_facts_lower.element in (:guestids) " +
            "and cp_consumer.owner_id = :ownerid " +
            "order by cp_consumer.updated desc";

        // We need to filter down to only the most recently registered consumer with
        // each guest ID.

        List<String> consumerUuids = new LinkedList<String>();

        Iterable<List<String>> blocks = Iterables.partition(
            possibleGuestIds, AbstractHibernateCurator.IN_OPERATOR_BLOCK_SIZE
        );

        Query query = this.currentSession()
            .createSQLQuery(sql)
            .setParameter("ownerid", owner.getId());

        for (List<String> block : blocks) {
            query.setParameterList("guestids", block);
            consumerUuids.addAll(query.list());
        }

        if (consumerUuids.isEmpty()) {
            return guestConsumersMap;
        }

        List<Consumer> guestConsumersWithDupes = findByUuidsAndOwner(consumerUuids, owner);
        // At this point we might have duplicates for re-registered consumers:
        for (Consumer c : guestConsumersWithDupes) {
            String virtUuid = c.getFact("virt.uuid").toLowerCase();
            if (guestConsumersMap.get(virtUuid) == null) {
                // Store both big and little endian forms in the result:
                guestConsumersMap.add(virtUuid, c);
            }

            // Can safely ignore if already in the map, this would be another consumer
            // reporting the same guest ID (re-registration), but we already sorted by
            // last update time.
        }

        return guestConsumersMap;
    }

    /**
     * Candlepin supports the notion of a user being a consumer. When in effect
     * a consumer will exist in the system who is tied to a particular user.
     *
     * @param user User
     * @return Consumer for this user if one exists, null otherwise.
     */
    @Transactional
    public Consumer findByUser(User user) {
        ConsumerType person = consumerTypeCurator
            .lookupByLabel(ConsumerType.ConsumerTypeEnum.PERSON.getLabel());

        return (Consumer) createSecureCriteria()
            .add(Restrictions.eq("username", user.getUsername()))
            .add(Restrictions.eq("type", person)).uniqueResult();
    }

    /**
     * Lookup the Consumer by its UUID.
     *
     * @param uuid Consumer UUID sought.
     * @return Consumer whose UUID matches the given value, or null otherwise.
     */
    @Transactional
    public Consumer findByUuid(String uuid) {
        return getConsumer(uuid);
    }

    /**
     * Apply a SELECT FOR UPDATE on a consumer.
     *
     * Note this method is not transactional.  It is meant to be used within
     * a larger transaction.  Starting a transaction, running a select for update,
     * and then ending the transaction is pointless.
     *
     * @return A consumer locked in the database
     */
    public Consumer lockAndLoad(Consumer c) {
        getEntityManager().lock(c, LockModeType.PESSIMISTIC_WRITE);
        return c;
    }

    /**
     * Find a consumer by uuid and immediately lock it.
     *
     * @param consumerUuid the uuid of the target consumer.
     *
     * @return the Consumer matching the given uuid, null if the consumer was not found.
     */
    public Consumer lockAndLoadByUuid(String consumerUuid) {
        List<Consumer> consumerList = lockAndLoadBatch(Arrays.asList(consumerUuid));
        return (CollectionUtils.isEmpty(consumerList)) ? null : consumerList.get(0);
    }

    public List<Consumer> lockAndLoadBatch(Collection<String> uuids) {
        return lockAndLoadBatch(uuids, "Consumer", "uuid");
    }

    @Transactional
    public List<Consumer> findByUuids(Collection<String> uuids) {
        return listByCriteria(
            createSecureCriteria().add(Restrictions.in("uuid", uuids)));
    }

    @Transactional
    public List<Consumer> findByUuidsAndOwner(Collection<String> uuids, Owner owner) {
        Criteria criteria = currentSession().createCriteria(Consumer.class);
        criteria.add(Restrictions.eq("owner", owner));
        criteria.add(Restrictions.in("uuid", uuids));
        return listByCriteria(criteria);
    }

    // NOTE: This is a giant hack that is for use *only* by SSLAuth in order
    // to bypass the authentication. Do not call it!
    // TODO: Come up with a better way to do this!
    public Consumer getConsumer(String uuid) {
        Criteria criteria = createSecureCriteria()
            .add(Restrictions.eq("uuid", uuid));

        return (Consumer) criteria.uniqueResult();
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public List<Consumer> listByOwner(Owner owner) {
        return createSecureCriteria()
            .add(Restrictions.eq("owner", owner)).list();
    }

    /**
     * Search for Consumers with fields matching those provided.
     *
     * @param userName the username to match, or null to ignore
     * @param types the types to match, or null/empty to ignore
     * @param owner Optional owner to filter on, pass null to skip.
     * @return a list of matching Consumers
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public Page<List<Consumer>> listByUsernameAndType(String userName,
        List<ConsumerType> types, Owner owner, PageRequest pageRequest) {

        Criteria criteria = createSecureCriteria();

        if (userName != null) {
            criteria.add(Restrictions.eq("username", userName));
        }
        if (types != null && !types.isEmpty()) {
            criteria.add(Restrictions.in("type", types));
        }
        if (owner != null) {
            criteria.add(Restrictions.eq("owner", owner));
        }

        return listByCriteria(criteria, pageRequest);
    }

    /**
     * @param updatedConsumer updated Consumer values.
     * @return Updated consumers
     */
    @Transactional
    public Consumer update(Consumer updatedConsumer) {
        return updateWithOptionalFlush(updatedConsumer, true);
    }

    /**
     * @param updatedConsumer updated Consumer values.
     * @param saveConsumer to flush or not to flush, that is the question.
     * @return Updated consumers
     */
    @Transactional
    public Consumer updateWithOptionalFlush(Consumer updatedConsumer, boolean saveConsumer) {
        Consumer existingConsumer = find(updatedConsumer.getId());
        if (existingConsumer == null) {
            return create(updatedConsumer);
        }

        // TODO: Are any of these read-only?
        existingConsumer.setEntitlements(entitlementCurator
            .bulkUpdate(updatedConsumer.getEntitlements()));
        Map<String, String> newFacts = filterAndVerifyFacts(updatedConsumer);
        if (factsChanged(newFacts, existingConsumer.getFacts())) {
            existingConsumer.setFacts(newFacts);
        }
        existingConsumer.setName(updatedConsumer.getName());
        existingConsumer.setOwner(updatedConsumer.getOwner());
        existingConsumer.setType(updatedConsumer.getType());
        existingConsumer.setUuid(updatedConsumer.getUuid());

        if (saveConsumer) {
            save(existingConsumer);
        }

        return existingConsumer;
    }
    /**
     * Modifies the last check in and persists the entity. Make sure that the data
     * is refreshed before using this method.
     * @param consumer the consumer to update
     */
    public void updateLastCheckin(Consumer consumer) {
        this.updateLastCheckin(consumer, new Date());
    }

    @Transactional
    public void updateLastCheckin(Consumer consumer, Date checkinDate) {
        currentSession().createQuery("update Consumer c " +
                "set c.lastCheckin = :date, " +
                "c.updated = :date " +
                "where c.id = :consumerid")
                .setTimestamp("date", checkinDate)
                .setParameter("consumerid", consumer.getId())
                .executeUpdate();
    }

    private boolean factsChanged(Map<String, String> updatedFacts,
        Map<String, String> existingFacts) {
        return !existingFacts.equals(updatedFacts);
    }

    /**
     * @param facts
     * @return the list of facts filtered by the fact filter regex config
     */
    private Map<String, String> filterAndVerifyFacts(Consumer consumer) {
        Map<String, String> factsIn = consumer.getFacts();
        Map<String, String> facts = new HashMap<String, String>();
        String factMatch = config.getString(ConfigProperties.CONSUMER_FACTS_MATCHER);
        Set<String> intFacts = config.getSet(ConfigProperties.INTEGER_FACTS);
        Set<String> posFacts = config.getSet(ConfigProperties.NON_NEG_INTEGER_FACTS);

        for (Entry<String, String> entry : factsIn.entrySet()) {
            if (entry.getKey().matches(factMatch)) {
                if (intFacts != null && intFacts.contains(entry.getKey()) ||
                    posFacts != null && posFacts.contains(entry.getKey())) {
                    int value = -1;
                    try {
                        value = Integer.parseInt(entry.getValue());
                    }
                    catch (NumberFormatException nfe) {
                        log.error("The fact " + entry.getKey() +
                            " for consumer " + consumer.getUuid() +
                            " must be an integer instead of " + entry.getValue() +
                            ". " + "No value will exist for that fact.");
                        continue;
                    }
                    if (posFacts != null && posFacts.contains(
                        entry.getKey()) &&
                        value < 0) {
                        log.error("The fact " + entry.getKey() +
                            " must have a positive integer value instead of " +
                            entry.getValue() + ". No value will exist for that fact.");
                        continue;
                    }
                }
                facts.put(sanitizeFact(entry.getKey()), sanitizeFact(entry.getValue()));
            }
        }
        return facts;
    }

    private String sanitizeFact(String value) {
        if (value != null && value.length() > MAX_FACT_STR_LENGTH) {
            return value.substring(0, MAX_FACT_STR_LENGTH - 3) + "...";
        }
        return value;
    }

    /**
     * @param consumers consumers to update
     * @return updated consumers
     */
    @Transactional
    public Set<Consumer> bulkUpdate(Set<Consumer> consumers) {
        Set<Consumer> toReturn = new HashSet<Consumer>();
        for (Consumer toUpdate : consumers) {
            toReturn.add(update(toUpdate));
        }
        return toReturn;
    }

    /**
     * Get host consumer for a guest system id.
     *
     * As multiple hosts could have reported the same guest ID, we find the newest
     * and assume this is the authoritative host for the guest.
     *
     * This search needs to be case insensitive as some hypervisors report uppercase
     * guest UUIDs, when the guest itself will report lowercase.
     *
     * @param guestId a virtual guest ID (not a consumer UUID)
     * @return host consumer who most recently reported the given guestId (if any)
     */
    @Transactional
    public Consumer getHost(String guestId, Owner owner) {
        Disjunction guestIdCrit = Restrictions.disjunction();
        for (String possibleId : Util.getPossibleUuids(guestId)) {
            guestIdCrit.add(Restrictions.eq("guestIdLower", possibleId.toLowerCase()));
        }
        Criteria crit = currentSession()
            .createCriteria(GuestId.class)
            .createAlias("consumer", "gconsumer")
            .add(Restrictions.eq("gconsumer.owner", owner))
            .addOrder(Order.desc("updated"))
            .setMaxResults(1)
            .setProjection(Projections.property("consumer"));
        return (Consumer) crit.add(guestIdCrit).uniqueResult();
    }

    /**
     * Get guest consumers for a host consumer.
     *
     * @param consumer host consumer to find the guests for
     * @return list of registered guest consumers for this host
     */
    @Transactional
    public List<Consumer> getGuests(Consumer consumer) {
        if (consumer.getFact("virt.uuid") != null &&
            !consumer.getFact("virt.uuid").trim().equals("")) {
            throw new BadRequestException(i18n.tr(
                "The system with UUID {0} is a virtual guest. " +
                "It does not have guests.",
                consumer.getUuid()));
        }
        List<Consumer> guests = new ArrayList<Consumer>();
        List<GuestId> consumerGuests = consumer.getGuestIds();
        if (consumerGuests != null) {
            for (GuestId cg : consumerGuests) {
                // Check if this is the most recent host to report the guest by asking
                // for the consumer's current host and comparing it to ourselves.
                if (consumer.equals(getHost(cg.getGuestId(), consumer.getOwner()))) {
                    Consumer guest = findByVirtUuid(cg.getGuestId(),
                        consumer.getOwner().getId());
                    if (guest != null) {
                        guests.add(guest);
                    }
                }
            }
        }
        return guests;
    }

    /**
     * This is an insecure query, because we need to know whether or not the
     * consumer exists
     *
     * We do not require that the hypervisor be consumerType hypervisor
     * because we need to allow regular consumers to be given
     * HypervisorIds to be updated via hypervisorResource
     *
     * @param hypervisorId Unique identifier of the hypervisor
     * @param owner Org namespace to search
     * @return Consumer that matches the given
     */
    @Transactional
    public Consumer getHypervisor(String hypervisorId, Owner owner) {
        return (Consumer) currentSession().createCriteria(Consumer.class)
            .add(Restrictions.eq("owner", owner))
            .createAlias("hypervisorId", "hvsr")
            .add(Restrictions.eq("hvsr.hypervisorId", hypervisorId.toLowerCase()))
            .setMaxResults(1)
            .uniqueResult();
    }

    /**
     * Lookup all registered consumers matching one of the given hypervisor IDs.
     *
     * Results are returned as a map of hypervisor ID to the consumer record created.
     * If a hypervisor ID is not in the map, this indicates the hypervisor consumer does
     * not exist, i.e. it is new and needs to be created.
     *
     * This is an unsecured query, manually limited to an owner by the parameter given.
     * @param owner Owner to limit results to.
     * @param hypervisorIds Collection of hypervisor IDs as reported by the virt fabric.
     *
     * @return VirtConsumerMap of hypervisor ID to it's consumer, or null if none exists.
     */
    @Transactional
    public VirtConsumerMap getHostConsumersMap(Owner owner, Iterable<String> hypervisorIds) {
        VirtConsumerMap hypervisorMap = new VirtConsumerMap();

        // TODO: Replace this with cursor bits when they're available!
        for (Consumer consumer : this.getHypervisorsBulk(hypervisorIds, owner.getKey())) {
            hypervisorMap.add(consumer.getHypervisorId().getHypervisorId(), consumer);
        }

        return hypervisorMap;
    }

    /**
     * @param hypervisorIds list of unique hypervisor identifiers
     * @param ownerKey Org namespace to search
     * @return Consumer that matches the given
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public List<Consumer> getHypervisorsBulk(Iterable<String> hypervisorIds, String ownerKey) {
        if (hypervisorIds == null || !hypervisorIds.iterator().hasNext()) {
            return new LinkedList<Consumer>();
        }

        return this.currentSession()
            .createCriteria(Consumer.class)
            .createAlias("owner", "o")
            .createAlias("hypervisorId", "hvsr")
            .add(Restrictions.eq("o.key", ownerKey))
            .add(this.getHypervisorIdRestriction(hypervisorIds))
            .list();
    }

    private Criterion getHypervisorIdRestriction(Iterable<String> hypervisorIds) {
        List<Criterion> ors = new LinkedList<Criterion>();
        for (String hypervisorId : hypervisorIds) {
            ors.add(Restrictions.eq("hvsr.hypervisorId", hypervisorId.toLowerCase()));
        }
        return Restrictions.or(ors.toArray(new Criterion[0]));
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public List<Consumer> getHypervisorsForOwner(String ownerKey) {
        return createSecureCriteria()
            .createAlias("owner", "o")
            .createAlias("hypervisorId", "hvsr")
            .add(Restrictions.eq("o.key", ownerKey))
            .add(Restrictions.isNotNull("hvsr.hypervisorId"))
            .list();
    }

    public boolean doesConsumerExist(String uuid) {
        long result = (Long) createSecureCriteria()
            .add(Restrictions.eq("uuid", uuid))
            .setProjection(Projections.count("id"))
            .uniqueResult();

        return result != 0;
    }

    public Consumer verifyAndLookupConsumer(String consumerUuid) {
        Consumer consumer = this.findByUuid(consumerUuid);

        if (consumer == null) {
            throw new NotFoundException(i18n.tr("Unit with ID ''{0}'' could not be found.", consumerUuid));
        }

        return consumer;
    }

    public Consumer verifyAndLookupConsumerWithEntitlements(String consumerUuid) {
        Consumer consumer = this.findByUuid(consumerUuid);
        if (consumer == null) {
            throw new NotFoundException(i18n.tr("Unit with ID ''{0}'' could not be found.", consumerUuid));
        }

        for (Entitlement e : consumer.getEntitlements()) {
            Hibernate.initialize(e.getCertificates());

            if (e.getPool() != null) {
                Hibernate.initialize(e.getPool().getBranding());
                Hibernate.initialize(e.getPool().getDerivedProvidedProducts());
                Hibernate.initialize(e.getPool().getProvidedProducts());
                Hibernate.initialize(e.getPool().getProductAttributes());
                Hibernate.initialize(e.getPool().getAttributes());
                Hibernate.initialize(e.getPool().getDerivedProductAttributes());
            }
        }

        return consumer;
    }

    @SuppressWarnings("checkstyle:indentation")
    public Page<List<Consumer>> searchOwnerConsumers(Owner owner, String userName,
        Collection<ConsumerType> types, List<String> uuids, List<String> hypervisorIds,
        List<KeyValueParameter> factFilters, List<String> skus,
        List<String> subscriptionIds, List<String> contracts, PageRequest pageRequest) {
        Criteria crit = super.createSecureCriteria();
        if (owner != null) {
            crit.add(Restrictions.eq("owner", owner));
        }
        if (userName != null && !userName.isEmpty()) {
            crit.add(Restrictions.eq("username", userName));
        }
        if (types != null && !types.isEmpty()) {
            crit.add(Restrictions.in("type", types));
        }
        if (uuids != null && !uuids.isEmpty()) {
            crit.add(Restrictions.in("uuid", uuids));
        }
        if (hypervisorIds != null && !hypervisorIds.isEmpty()) {
            // Cannot use Restrictions.in here because hypervisorId is case insensitive
            Set<Criterion> ors = new HashSet<Criterion>();
            for (String hypervisorId : hypervisorIds) {
                ors.add(Restrictions.eq("hvsr.hypervisorId", hypervisorId.toLowerCase()));
            }
            crit.createAlias("hypervisorId", "hvsr");
            crit.add(Restrictions.or(ors.toArray(new Criterion[ors.size()])));
        }
        if (factFilters != null && !factFilters.isEmpty()) {
            // Process the filters passed for the attributes
            FilterBuilder factFilter = new FactFilterBuilder();
            for (KeyValueParameter filterParam : factFilters) {
                factFilter.addAttributeFilter(filterParam.key(), filterParam.value());
            }
            factFilter.applyTo(crit);
        }

        boolean hasSkus = (skus != null && !skus.isEmpty());
        boolean hasSubscriptionIds = (subscriptionIds != null && !subscriptionIds.isEmpty());
        boolean hasContractNumbers = (contracts != null && !contracts.isEmpty());

        if (hasSkus || hasSubscriptionIds || hasContractNumbers) {
            if (hasSkus) {
                for (String sku : skus) {
                    DetachedCriteria subCrit = DetachedCriteria.forClass(Consumer.class, "subquery_consumer");

                    if (owner != null) {
                        subCrit.add(Restrictions.eq("owner", owner));
                    }

                    subCrit.createCriteria("entitlements")
                        .createCriteria("pool")
                        .createCriteria("product").add(Restrictions.eq("id", sku))
                        .createCriteria("attributes").add(
                            Restrictions.and(
                                Restrictions.eq("name", "type"),
                                Restrictions.eq("value", "MKT"))
                        );

                    subCrit.add(Restrictions.eqProperty("this.id", "subquery_consumer.id"));

                    crit.add(Subqueries.exists(
                        subCrit.setProjection(Projections.property("subquery_consumer.name")))
                    );
                }
            }
            if (hasSubscriptionIds) {
                for (String subId : subscriptionIds) {
                    DetachedCriteria subCrit = DetachedCriteria.forClass(Consumer.class, "subquery_consumer");

                    if (owner != null) {
                        subCrit.add(Restrictions.eq("owner", owner));
                    }

                    subCrit.createCriteria("entitlements").createCriteria("pool")
                        .createCriteria("sourceSubscription").add(Restrictions.eq("subscriptionId", subId));
                    subCrit.add(Restrictions.eqProperty("this.id", "subquery_consumer.id"));

                    crit.add(Subqueries.exists(
                        subCrit.setProjection(Projections.property("subquery_consumer.name")))
                    );
                }
            }
            if (hasContractNumbers) {
                for (String contract : contracts) {
                    DetachedCriteria subCrit = DetachedCriteria.forClass(Consumer.class, "subquery_consumer");

                    if (owner != null) {
                        subCrit.add(Restrictions.eq("owner", owner));
                    }

                    subCrit.createCriteria("entitlements").createCriteria("pool").add(
                        Restrictions.eq("contractNumber", contract)
                    );
                    subCrit.add(Restrictions.eqProperty("this.id", "subquery_consumer.id"));

                    crit.add(Subqueries.exists(
                        subCrit.setProjection(Projections.property("subquery_consumer.name")))
                    );
                }
            }
        }

        return listByCriteria(crit, pageRequest);
    }

    /**
     * Finds the consumer count for an Owner based on type.
     *
     * @param owner the owner to count consumers for
     * @param type the type of the Consumer to filter on.
     * @return the number of consumers based on the type.
     */
    public int getConsumerCount(Owner owner, ConsumerType type) {
        Criteria c = createSecureCriteria()
            .add(Restrictions.eq("owner", owner))
            .add(Restrictions.eq("type", type));
        c.setProjection(Projections.rowCount());
        return ((Long) c.uniqueResult()).intValue();
    }

    public int getConsumerEntitlementCount(Owner owner, ConsumerType type) {
        Criteria c = createSecureCriteria()
            .add(Restrictions.eq("owner", owner))
            .add(Restrictions.eq("type", type))
            .createAlias("entitlements", "ent")
            .setMaxResults(0)
            .setProjection(Projections.sum("ent.quantity"));
        Long result = (Long) c.uniqueResult();
        return result == null ? 0 : result.intValue();
    }

    @SuppressWarnings("unchecked")
    public List<String> getConsumerIdsWithStartedEnts() {
        Date now = new Date();
        return currentSession().createCriteria(Entitlement.class)
            .createAlias("pool", "p")
            .add(Restrictions.eq("updatedOnStart", false))
            .add(Restrictions.lt("p.startDate", now))
            .setProjection(Projections.property("consumer.id"))
            .setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY)
            .list();
    }


}
