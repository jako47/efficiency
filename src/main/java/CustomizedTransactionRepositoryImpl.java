package za.co.transactionjunction.reconenginev2.repositories;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import za.co.transactionjunction.reconenginev2.dto.results.AccountTypeResults;
import za.co.transactionjunction.reconenginev2.dto.results.ProviderResults;
import za.co.transactionjunction.reconenginev2.dto.results.ReconResults;
import za.co.transactionjunction.reconenginev2.dto.results.SchemaResults;
import za.co.transactionjunction.reconenginev2.dto.supers.ReconTotals;
import za.co.transactionjunction.reconenginev2.entities.ApiStatsEntity;
import za.co.transactionjunction.reconenginev2.entities.TransactionEntity;
import za.co.transactionjunction.reconenginev2.models.ReconStatus;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CustomizedTransactionRepositoryImpl implements CustomizedTransactionRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public Stream<TransactionEntity> streamAll(Specification<TransactionEntity> spec) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TransactionEntity> query = cb.createQuery(TransactionEntity.class);
        Root<TransactionEntity> root = query.from(TransactionEntity.class);

        return entityManager.createQuery(query.where(spec.toPredicate(root, query, cb)))
                .getResultStream();
    }

    public Stream<TransactionEntity> streamTop(Specification<TransactionEntity> spec, int limit) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TransactionEntity> query = cb.createQuery(TransactionEntity.class);
        Root<TransactionEntity> root = query.from(TransactionEntity.class);

        return entityManager.createQuery(query.where(spec.toPredicate(root, query, cb)))
                .setMaxResults(limit)
                .getResultStream();
    }

    public List<Tuple> findSettlementList(String[] filterBy, String filterValue, String orderBy, Boolean orderDir, Short limit, Integer offset, List<String> permissions) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<TransactionEntity> transaction = query.from(TransactionEntity.class);
        Expression<Timestamp> markoffDate = cb.function("DATE_TRUNC", Timestamp.class, cb.literal("day"), transaction.get("markoffTimestamp"));
        query.multiselect(
                transaction.get("clientId").alias("clientId"),
                markoffDate.alias("markoffDate"),
                transaction.get("providerBatchNumber").alias("providerBatchNumber"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(transaction.get("markoffStatus").in(2, 3, 5), transaction.get("clientTransactionAmount"))
                                .otherwise(0L)
                ).alias("totalAmount"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(transaction.get("markoffStatus").in(2, 3, 5), 1L)
                                .otherwise(0L)
                ).alias("countTransactions"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(transaction.get("markoffStatus").in(3, 5), 1L)
                                .otherwise(0L)
                ).alias("countExceptions")
        );
        List<Predicate> predicates = new ArrayList<>();
        if (filterBy != null && filterValue != null) {
            predicates.add(cb.equal(transaction.get(Arrays.toString(filterBy)), filterValue));
        }
        if (!permissions.isEmpty()) {
            predicates.add(transaction.get("clientId").in(permissions));
        }
        if (!predicates.isEmpty()) {
            query.where(cb.and(predicates.toArray(new Predicate[0])));
        }
        query.groupBy(
                transaction.get("clientId"),
                markoffDate,
                transaction.get("providerBatchNumber")
        );
        if (orderBy != null) {
            Order order = orderDir != null && !orderDir
                    ? cb.desc(cb.max(transaction.get(orderBy)))
                    : cb.asc(cb.max(transaction.get(orderBy)));
            query.orderBy(order);
        }

        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
        if (limit != null) {
            typedQuery.setMaxResults(limit);
        }
        if (offset != null) {
            typedQuery.setFirstResult(offset);
        }

        return typedQuery.getResultList();
    }


    @Override
    public List<Tuple> findReconAnalysisResults(LocalDateTime transactionDate, String[] filterBy, String filterValue, String orderBy, Boolean orderDir, List<String> permissions, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<TransactionEntity> transaction = query.from(TransactionEntity.class);
        query.multiselect(
                transaction.get("clientId").alias("clientStoreId"),
                transaction.get("clientBinBank").alias("providerStoreId"),
                transaction.get("providerBatchNumber").alias("providerBatchId"),
                transaction.get("consolidatedTransactionTimestamp").alias("transactionDate"),
                transaction.get("binCardType").alias("cardType"),
                transaction.get("binAccountType").alias("accountType"),
                cb.literal("Card Transaction").alias("profileDescription"), //TODO Ensure can stay static
                cb.sum(
                        cb.<Integer>selectCase()
                                .when(transaction.get("markoffStatus").in(2), 1)
                                .otherwise(0)
                ).alias("countMatched"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(transaction.get("markoffStatus").in(2), transaction.get("clientTransactionAmount"))
                                .otherwise(0L)
                ).alias("totalMatched"),
                cb.sum(
                        cb.<Integer>selectCase()
                                .when(transaction.get("markoffStatus").in(3), 1)
                                .otherwise(0)
                ).alias("countExceptionMatchedNotEqual"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(transaction.get("markoffStatus").in(3), transaction.get("clientTransactionAmount"))
                                .otherwise(0L)
                ).alias("totalExceptionNotEqualClient"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(transaction.get("markoffStatus").in(4), transaction.get("clientTransactionAmount"))
                                .otherwise(0L)
                ).alias("totalExceptionClientOnly"),
                cb.sum(
                        cb.<Integer>selectCase()
                                .when(transaction.get("markoffStatus").in(4), 1)
                                .otherwise(0)
                ).alias("countExceptionClientOnly"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(transaction.get("markoffStatus").in(5), transaction.get("clientTransactionAmount"))
                                .otherwise(0L)
                ).alias("totalExceptionProviderOnly"),
                cb.sum(
                        cb.<Integer>selectCase()
                                .when(transaction.get("markoffStatus").in(5), 1)
                                .otherwise(0)
                ).alias("countExceptionProviderOnly"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(transaction.get("markoffStatus").in(3), transaction.get("clientTransactionAmount"))
                                .otherwise(0L)
                ).alias("totalExceptionMatchedNotEqualProvider")
        );
        query.groupBy(
                transaction.get("clientId"),
                transaction.get("clientBinBank"),
                transaction.get("providerBatchNumber"),
                transaction.get("consolidatedTransactionTimestamp"),
                transaction.get("binAccountType"),
                transaction.get("markoffTimestamp"),
                transaction.get("binCardType")

        );
        List<Predicate> predicates = new ArrayList<>();

        if (permissions != null && !permissions.isEmpty()) {
            CriteriaBuilder.In<String> inClause = cb.in(transaction.get("clientId"));
            for (String permission : permissions) {
                inClause.value(permission);
            }
            predicates.add(inClause);
        }

        if (transactionDate != null) {
            predicates.add(cb.greaterThan(transaction.get("consolidatedTransactionTimestamp"), Timestamp.valueOf(transactionDate)));
        }
        if (filterBy != null && filterValue != null) {
            predicates.add(cb.equal(transaction.get(Arrays.toString(filterBy)), filterValue));
        }
        if (!predicates.isEmpty()) {
            query.where(cb.and(predicates.toArray(new Predicate[0])));
        }
        if (orderBy != null) {
            Order order = orderDir != null && !orderDir ? cb.desc(transaction.get(orderBy)) : cb.asc(transaction.get(orderBy));
            query.orderBy(order);
        }
        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
        if (pageable != null) {
            typedQuery.setFirstResult((int) pageable.getOffset());
            typedQuery.setMaxResults(pageable.getPageSize());
        }
        return typedQuery.getResultList();
    }

    @Override
    public List<Tuple> findReconSettlementsByBatchId(String reconBatchId, String[] filterBy, String filterValue, String orderBy, Boolean orderDir, Short limit, Integer offset, List<String> permissions) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<TransactionEntity> transaction = query.from(TransactionEntity.class);

        query.multiselect(
                cb.literal("Placeholder").alias("processId"),
                cb.literal("Placeholder").alias("importJobId"),
                transaction.get("providerBatchNumber").alias("reconBatchId"),
                transaction.get("markoffTimestamp").alias("reconTime"),
                transaction.get("clientId").alias("storeId"),
                cb.literal("Placeholder").alias("cardType"),
                transaction.get("binAccountType").alias("accountType"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(cb.equal(transaction.get("markoffStatus"), 2), 1L)
                                .otherwise(0L)
                ).alias("countMatched"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(cb.equal(transaction.get("markoffStatus"), 2), transaction.get("clientTransactionAmount"))
                                .otherwise(0L)
                ).alias("totalMatched"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(cb.equal(transaction.get("markoffStatus"), 3), 1L)
                                .otherwise(0L)
                ).alias("countExceptionMatchedNotEqual"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(cb.equal(transaction.get("markoffStatus"), 3), transaction.get("clientTransactionAmount"))
                                .otherwise(0L)
                ).alias("totalExceptionMatchedNotEqual"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(cb.equal(transaction.get("markoffStatus"), 5), 1L)
                                .otherwise(0L)
                ).alias("countExceptionImportOnly"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(cb.equal(transaction.get("markoffStatus"), 5), transaction.get("clientTransactionAmount"))
                                .otherwise(0L)
                ).alias("totalExceptionImportOnly"),
                cb.sum(
                        cb.<Long>selectCase()
                                .when(transaction.get("markoffStatus").in(2, 3, 5), transaction.get("clientTransactionAmount"))
                                .otherwise(0L)
                ).alias("totalAmount")
        );

        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(transaction.get("providerBatchNumber"), reconBatchId));

        if (permissions != null && !permissions.isEmpty()) {
            CriteriaBuilder.In<String> inClause = cb.in(transaction.get("clientId"));
            for (String permission : permissions) {
                inClause.value(permission);
            }
            predicates.add(inClause);
        }

        if (filterBy != null && filterValue != null) {
            predicates.add(cb.equal(transaction.get(Arrays.toString(filterBy)), filterValue));
        }

        query.where(cb.and(predicates.toArray(new Predicate[0])));

        query.groupBy(
                transaction.get("providerBatchNumber"),
                transaction.get("markoffTimestamp"),
                transaction.get("clientId"),
                transaction.get("providerAcctType"),
                transaction.get("binAccountType")
        );

        if (orderBy != null) {
            Order order = (orderDir != null && !orderDir) ? cb.desc(transaction.get(orderBy)) : cb.asc(transaction.get(orderBy));
            query.orderBy(order);
        }

        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);

        if (limit != null) {
            typedQuery.setMaxResults(limit);
        }
        if (offset != null) {
            typedQuery.setFirstResult(offset);
        }

        return typedQuery.getResultList();
    }
    /**
     * Retrieves a list of reconciliation results based on the specified filters and grouping criteria.
     * <p>
     * This method constructs and executes a dynamic JPA Criteria API query to aggregate transaction
     * data from the {@code TransactionEntity} table. The process involves the following steps:
     * </p>
     * <ol>
     *   <li>
     *     <b>Building the Query Selections:</b>
     *     <p>
     *     The method invokes {@link #buildQuerySelections(CriteriaQuery, CriteriaBuilder, Root, Boolean)}
     *     to add both the basic selections (such as scheme, account type, provider, and total transaction
     *     count) and a series of common aggregated selections (e.g., counts and sums for matched,
     *     exceptions, excluded, and new transactions) via the helper method
     *     {@link #buildCommonSelections(CriteriaBuilder, Root)}.
     *     </p>
     *   </li>
     *   <li>
     *     <b>Creating Query Predicates:</b>
     *     <p>
     *     A list of predicates is built to filter the data:
     *     </p>
     *     <ul>
     *       <li>
     *         <em>Date Range:</em> Filters transactions based on the {@code consolidatedTransactionTimestamp}
     *         to include only those between {@code fromDate} and {@code toDate}.
     *       </li>
     *       <li>
     *         <em>Markoff Status:</em> Filters based on the transaction's markoff status by invoking
     *         {@link #buildMarkoffStatusPredicate(CriteriaBuilder, Root, List, Boolean, Boolean)}. If no
     *         specific statuses are provided, it defaults to including transactions with the following
     *         markoff statuses:
     *         <ul>
     *           <li>{@code 1} – New</li>
     *           <li>{@code 2} – Reconciled</li>
     *           <li>{@code 3} – Partial_Match</li>
     *           <li>{@code 4} – Not_Matched</li>
     *           <li>{@code 5} – Provider_Only (Merchant Transactions Missing)</li>
     *           <li>{@code 6} – Excluded_From_Recon</li>
     *         </ul>
     *         Additional statuses may be added based on flags:
     *         <ul>
     *           <li>{@code 7} – Unsettled_By_Bank</li>
     *           <li>{@code 8} – Finally_Settled_By_Bank</li>
     *           <li>{@code 9} – Reversal</li>
     *         </ul>
     *         <p>
     *         If specific statuses are provided in the {@code transactionStatuses} parameter, the method
     *         iterates over them (using a switch-case) and creates predicates for each. For example,
     *         when the status is "Provider_Only", it builds a compound predicate to include:
     *         <ul>
     *           <li>Transactions with {@code markoffStatus} = 4 <em>and</em> {@code transactionSource} = 2, or</li>
     *           <li>Transactions with {@code markoffStatus} = 5</li>
     *         </ul>
     *         </p>
     *       </li>
     *       <li>
     *         <em>Permissions:</em> Limits transactions to those whose {@code clientId} is in the provided
     *         list of {@code permissions}.
     *       </li>
     *       <li>
     *         <em>Terminal IDs, Transaction Types, Tender Types, and Account Types:</em> If specified, the
     *         query further restricts transactions to those matching any of the provided terminal IDs (on
     *         either {@code clientTerminalId} or {@code providerTerminalId}), transaction types, tender
     *         types (based on {@code binCardType}), and account types (based on {@code binAccountType}).
     *       </li>
     *     </ul>
     *   </li>
     *   <li>
     *     <b>Grouping the Results:</b>
     *     <p>
     *     Based on the {@code dateGrouping} flag, the query results are grouped differently:
     *     </p>
     *     <ul>
     *       <li>
     *         If {@code dateGrouping} is {@code true}, the results are grouped by the date portion of the
     *         {@code consolidatedTransactionTimestamp}, along with scheme, account type, and provider.
     *       </li>
     *       <li>
     *         If {@code dateGrouping} is {@code false}, the grouping is done by the {@code clientId},
     *         {@code merchantName}, scheme, account type, and provider.
     *       </li>
     *     </ul>
     *   </li>
     *   <li>
     *     <b>Executing and Mapping the Query:</b>
     *     <p>
     *     After constructing the query with its selections, predicates, and grouping, the method executes
     *     the query using the {@code EntityManager} to retrieve a list of {@link Tuple} objects. These
     *     tuples are then transformed into a hierarchical structure of reconciliation results using the
     *     helper method {@link #mapToReconResults(List, Boolean)}, which internally maps each tuple to
     *     an {@link AccountTypeResults} (via {@link #mapAccountTypeResults(Tuple)}), and aggregates the data
     *     into higher-level {@link ReconResults} objects.
     *     </p>
     *   </li>
     * </ol>
     *
     * <p>
     * <strong>Markoff Status Mapping:</strong>
     * The {@code markoffStatus} field in the {@code TransactionEntity} is used to indicate the reconciliation
     * status of a transaction. The numeric values correspond to the following statuses:
     * </p>
     * <ul>
     *   <li>{@code 0} – Unknown</li>
     *   <li>{@code 1} – New</li>
     *   <li>{@code 2} – Reconciled (Matched)</li>
     *   <li>{@code 3} – Partial_Match (Matched Not Equal)</li>
     *   <li>{@code 4} – Not_Matched</li>
     *   <li>{@code 5} – Provider_Only (Merchant Transactions Missing)</li>
     *   <li>{@code 6} – Excluded_From_Recon (Excluded)</li>
     *   <li>{@code 7} – Unsettled_By_Bank</li>
     *   <li>{@code 8} – Finally_Settled_By_Bank</li>
     *   <li>{@code 9} – Reversal</li>
     * </ul>
     *
     * @param permissions         A list of client IDs; only transactions whose {@code clientId} is in this list
     *                            will be considered.
     * @param fromDate            The start date and time (inclusive) for filtering transactions based on their
     *                            {@code consolidatedTransactionTimestamp}.
     * @param toDate              The end date and time (inclusive) for filtering transactions based on their
     *                            {@code consolidatedTransactionTimestamp}.
     * @param dateGrouping        If {@code true}, results will be grouped by the date (ignoring time) along with
     *                            scheme, account type, and provider; if {@code false}, results are grouped by store
     *                            (clientId and merchantName) along with scheme, account type, and provider.
     * @param terminalIds         A list of terminal IDs to filter the transactions. If provided and non-empty,
     *                            transactions must match at least one terminal ID in either the {@code clientTerminalId}
     *                            or {@code providerTerminalId} field.
     * @param transactionStatuses A list of transaction status strings (e.g., "New", "Reconciled", "Partial_Match",
     *                            "Not_Matched", "Provider_Only", "Excluded_From_Recon", "Unsettled_By_Bank",
     *                            "Finally_Settled_By_Bank", "Reversal", "Store_Only"). These values are used in the
     *                            {@link #buildMarkoffStatusPredicate(CriteriaBuilder, Root, List, Boolean, Boolean)} method
     *                            to filter transactions by their corresponding numeric {@code markoffStatus}.
     * @param transactionTypes    A list of transaction types to filter on.
     * @param tenderTypes         A list of tender types (mapped to {@code binCardType}) to filter on.
     * @param accountTypes        A list of account types (mapped to {@code binAccountType}) to filter on.
     * @return A list of {@link ReconResults} objects, each representing a grouped set of reconciliation totals.
     *         The results are hierarchically structured by date or store, then by provider, and then by scheme.
     *
     * @see #buildQuerySelections(CriteriaQuery, CriteriaBuilder, Root, Boolean)
     * @see #buildCommonSelections(CriteriaBuilder, Root)
     * @see #buildMarkoffStatusPredicate(CriteriaBuilder, Root, List, Boolean, Boolean)
     * @see #mapToReconResults(List, Boolean)
     * @see #mapAccountTypeResults(Tuple)
     *
     * <p>
     * <strong>Process Overview:</strong>
     * <br>
     * 1. <em>Query Construction:</em> Using the Criteria API, the method selects various columns (both
     * basic and aggregated) from the {@code TransactionEntity}.
     * <br>
     * 2. <em>Filtering:</em> Predicates are applied for the date range, markoff statuses (using a default
     * set if none are provided), permissions, and optionally terminal IDs, transaction types, tender types,
     * and account types.
     * <br>
     * 3. <em>Grouping:</em> Depending on the {@code dateGrouping} flag, the results are grouped either by
     * date or by store information along with scheme, account type, and provider.
     * <br>
     * 4. <em>Execution and Mapping:</em> The query is executed, and the raw tuples are transformed into a
     * hierarchical structure of reconciliation results, aggregating the totals for various markoff statuses
     * (as defined above).
     * </p>
     */
    public List<ReconResults> findReconResults(
            List<String> permissions,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            Boolean dateGrouping,
            List<String> terminalIds,
            List<String> transactionStatuses,
            List<String> transactionTypes,
            List<String> tenderTypes,
            List<String> accountTypes,
            ApiStatsEntity apiStatsEntity) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<TransactionEntity> transaction = query.from(TransactionEntity.class);

        // Build query selections
        buildQuerySelections(query, cb, transaction, dateGrouping);

        // Initialize predicates
        List<Predicate> predicates = new ArrayList<>();

        // Add date range filter
        predicates.add(cb.between(
                transaction.get("consolidatedTransactionTimestamp"),
                cb.literal(fromDate),
                cb.literal(toDate)
        ));

        // Add markoffStatus filter
        predicates.add(buildMarkoffStatusPredicate(cb, transaction, transactionStatuses, false, true));

        // Add permissions filter
        predicates.add(transaction.get("clientId").in(permissions));

        // Add terminalIds filter
        if (terminalIds != null && !terminalIds.isEmpty() && !terminalIds.contains("")) {
            predicates.add(cb.or(
                    transaction.get("clientTerminalId").in(terminalIds),
                    transaction.get("providerTerminalId").in(terminalIds)
            ));
        }

        // Add transactionTypes filter
        if (transactionTypes != null && !transactionTypes.isEmpty()) {
            predicates.add(transaction.get("transactionType").in(transactionTypes));
        }

        // Add tenderTypes filter
        if (tenderTypes != null && !tenderTypes.isEmpty()) {
            predicates.add(transaction.get("binCardType").in(tenderTypes));
        }

        // Add accountTypes filter
        if (accountTypes != null && !accountTypes.isEmpty()) {
            predicates.add(transaction.get("binAccountType").in(accountTypes));
        }

        // Combine predicates into the query
        query.where(cb.and(predicates.toArray(new Predicate[0])));

        // Group results based on the dateGrouping flag
        addGrouping(query, cb, transaction, dateGrouping);

        // Execute query and map results
        List<Tuple> results = entityManager.createQuery(query).getResultList();

        //Stats
        apiStatsEntity.setRecordCount(Long.valueOf(results.size()));
        return mapToReconResults(results, dateGrouping);
    }

    private Predicate buildMarkoffStatusPredicate(CriteriaBuilder cb, Root<TransactionEntity> transaction, List<String> reconStatuses, Boolean showOnlyExceptions, Boolean includeExceptions) {
        if (reconStatuses == null || reconStatuses.isEmpty()) {
            return transaction.get("markoffStatus").in(1, 2, 3, 4, 5, 6);
        }

        List<Predicate> predicates = new ArrayList<>();

        for (String status : reconStatuses) {

            switch (status) {
                case "New" -> {
                    if (includeExceptions)
                        predicates.add(cb.equal(transaction.get("markoffStatus"), 1));
                }
                case "Reconciled" -> {
                    if (!showOnlyExceptions)
                        predicates.add(cb.equal(transaction.get("markoffStatus"), 2));
                }
                case "Partial_Match" -> {
                    if (includeExceptions)
                        predicates.add(cb.equal(transaction.get("markoffStatus"), 3));
                }
                case "Not_Matched" -> {
                    if (includeExceptions)
                        predicates.add(cb.equal(transaction.get("markoffStatus"), 4));
                }
                case "Provider_Only" -> {
                    if (includeExceptions) {
                        Predicate providerOnlyPredicate = cb.and(
                                cb.equal(transaction.get("markoffStatus"), 4),
                                cb.equal(transaction.get("transactionSource"), 2)
                        );
                        Predicate statusFivePredicate = cb.equal(transaction.get("markoffStatus"), 5);
                        predicates.add(cb.or(providerOnlyPredicate, statusFivePredicate));
                    }
                }
                case "Excluded_From_Recon" -> {
                    predicates.add(cb.equal(transaction.get("markoffStatus"), 6));
                }
                case "Unsettled_By_Bank" -> {
                    if (!showOnlyExceptions)
                        predicates.add(cb.equal(transaction.get("markoffStatus"), 7));
                }
                case "Finally_Settled_By_Bank" -> {
                    if (!showOnlyExceptions)
                        predicates.add(cb.equal(transaction.get("markoffStatus"), 8));
                }
                case "Reversal" -> {
                    if (!showOnlyExceptions)
                        predicates.add(cb.equal(transaction.get("markoffStatus"), 9));
                }
                case "Store_Only" -> {
                    if (includeExceptions)
                        predicates.add(cb.and(
                                cb.equal(transaction.get("markoffStatus"), 4),
                                cb.equal(transaction.get("transactionSource"), 1)
                        ));
                }
                default -> {
                    throw new IllegalArgumentException("Invalid recon status: " + status);
                }
            }
        }

        return cb.or(predicates.toArray(new Predicate[0]));
    }

    private void addGrouping(CriteriaQuery<Tuple> query, CriteriaBuilder cb, Root<TransactionEntity> transaction, Boolean dateGrouping) {
        if (dateGrouping) {
            query.groupBy(
                    cb.function("DATE", Timestamp.class, transaction.get("consolidatedTransactionTimestamp")),
                    transaction.get("binCardType"),
                    transaction.get("binAccountType"),
                    transaction.get("consolidatedBank")
            );
        } else {
            query.groupBy(
                    transaction.get("clientId"),
                    transaction.get("merchantName"),
                    transaction.get("binCardType"),
                    transaction.get("binAccountType"),
                    transaction.get("consolidatedBank")
            );
        }
    }

    private void buildQuerySelections(CriteriaQuery<Tuple> query, CriteriaBuilder cb, Root<TransactionEntity> transaction, Boolean dateGrouping) {
        List<Selection<?>> selections = new ArrayList<>();

        // Select based on dateGrouping flag
        if (dateGrouping) {
            selections.add(cb.function("DATE", Timestamp.class, transaction.get("consolidatedTransactionTimestamp")).alias("currentDay"));
        } else {
            selections.add(transaction.get("clientId").alias("storeId"));
            selections.add(cb.trim(transaction.get("merchantName")).alias("merchantName"));
        }

        // Add basic selections
        selections.add(transaction.get("binCardType").alias("scheme"));
        selections.add(transaction.get("binAccountType").alias("accountType"));
        selections.add(transaction.get("consolidatedBank").alias("provider"));
        selections.add(cb.count(transaction).alias("totalTransactions"));


        // Add common selections (totals for exceptions, matched transactions, etc.)
        selections.addAll(Arrays.asList(buildCommonSelections(cb, transaction)));

        // Set the final selections
        query.multiselect(selections.toArray(new Selection<?>[0]));
    }

    private Selection<?>[] buildCommonSelections(CriteriaBuilder cb, Root<TransactionEntity> transaction) {
        return new Selection<?>[]{
                //Excluded Transactions
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), 6), cb.literal(1))
                        .otherwise(cb.literal(0)).as(Long.class)).alias("countExcludedTransactions"),

                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), 6), transaction.<BigDecimal>get("clientTransactionAmount"))
                        .otherwise(cb.literal(BigDecimal.ZERO)).as(BigDecimal.class)).alias("totalExcludedTransactionsAmount"),

                // Count of provider-only exceptions (status = 5)
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), 5), cb.literal(1))
                        .otherwise(cb.literal(0)).as(Long.class)).alias("countProviderOnlyExceptionsStatus5"),

                // Count of provider-only exceptions (status = 4 AND source = 2)
                cb.sum(cb.selectCase()
                        .when(
                                cb.and(
                                        cb.equal(transaction.get("markoffStatus"), 4),
                                        cb.equal(transaction.get("transactionSource"), 2)
                                ),
                                cb.literal(1)
                        )
                        .otherwise(cb.literal(0)).as(Long.class)).alias("countProviderOnlyExceptionsStatus4"),

                // Total provider-only exceptions amount (status = 5)
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), 5), transaction.<BigDecimal>get("providerMarkOffAmount"))
                        .otherwise(cb.literal(BigDecimal.ZERO)).as(BigDecimal.class)).alias("totalProviderOnlyExceptionsAmountStatus5"),

                // Total provider-only exceptions amount (status = 4 AND source = 2)
                cb.sum(cb.selectCase()
                        .when(
                                cb.and(
                                        cb.equal(transaction.get("markoffStatus"), 4),
                                        cb.equal(transaction.get("transactionSource"), 2)
                                ),
                                transaction.<BigDecimal>get("clientTransactionAmount")
                        )
                        .otherwise(cb.literal(BigDecimal.ZERO)).as(BigDecimal.class)).alias("totalProviderOnlyExceptionsAmountStatus4"),

                // Count of New Transactions (status = 1)
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), 1), cb.literal(1))
                        .otherwise(cb.literal(0)).as(Long.class)).alias("countNewTransactions"),

                // Count of client-only exceptions (status = 4 AND source = 1)
                cb.sum(cb.selectCase()
                        .when(
                                cb.and(
                                        cb.equal(transaction.get("markoffStatus"), 4),
                                        cb.equal(transaction.get("transactionSource"), 1)
                                ),
                                cb.literal(1)
                        )
                        .otherwise(cb.literal(0)).as(Long.class)).alias("countClientOnlyExceptionsStatus4Source1"),

                // Total client-only new amount (status = 1)
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), 1), transaction.<BigDecimal>get("clientTransactionAmount"))
                        .otherwise(cb.literal(BigDecimal.ZERO)).as(BigDecimal.class)).alias("totalNewTransactionsClientAmount"),

                // Total client-only exceptions amount (status = 4 AND source = 1)
                cb.sum(cb.selectCase()
                        .when(
                                cb.and(
                                        cb.equal(transaction.get("markoffStatus"), 4),
                                        cb.equal(transaction.get("transactionSource"), 1)
                                ),
                                transaction.<BigDecimal>get("clientTransactionAmount")
                        )
                        .otherwise(cb.literal(BigDecimal.ZERO)).as(BigDecimal.class)).alias("totalClientOnlyExceptionsAmountStatus4Source1"),

                // Count of matched but not equal (status = 3)
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), 3), cb.literal(1))
                        .otherwise(cb.literal(0)).as(Long.class)).alias("countMatchedNotEqual"),

                // Total matched but not equal (status = 3, provider amount)
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), 3), transaction.<BigDecimal>get("providerMarkOffAmount"))
                        .otherwise(cb.literal(BigDecimal.ZERO)).as(BigDecimal.class)).alias("totalMatchedNotEqualProviderAmount"),

                // Total matched but not equal (status = 3, client amount)
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), 3), transaction.<BigDecimal>get("clientTransactionAmount"))
                        .otherwise(cb.literal(BigDecimal.ZERO)).as(BigDecimal.class)).alias("totalMatchedNotEqualClientAmount"),

                // Total exceptions (statuses 3, 4, 5)
                cb.sum(cb.selectCase()
                        .when(transaction.get("markoffStatus").in(3, 4, 5), cb.literal(1))
                        .otherwise(cb.literal(0)).as(Long.class)).alias("exceptionCount"),

                cb.sum(cb.selectCase()
                        .when(transaction.get("markoffStatus").in(3, 4, 5), transaction.<BigDecimal>get("providerMarkOffAmount"))
                        .otherwise(cb.literal(BigDecimal.ZERO)).as(BigDecimal.class)).alias("exceptionTotalMarkOffAmount"),

                cb.sum(cb.selectCase()
                        .when(transaction.get("markoffStatus").in(3, 4, 5), transaction.<BigDecimal>get("clientTransactionAmount"))
                        .otherwise(cb.literal(BigDecimal.ZERO)).as(BigDecimal.class)).alias("exceptionTotalTransactionAmount"),

                // Matched transactions (status = 2)
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), 2), cb.literal(1))
                        .otherwise(cb.literal(0)).as(Long.class)).alias("matchedTransaction"),

                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), 2), transaction.<BigDecimal>get("providerMarkOffAmount"))
                        .otherwise(cb.literal(BigDecimal.ZERO)).as(BigDecimal.class)).alias("totalMatchedTransactionsProviderAmount"),

                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), 2), transaction.<BigDecimal>get("clientTransactionAmount"))
                        .otherwise(cb.literal(BigDecimal.ZERO)).as(BigDecimal.class)).alias("totalMatchedTransactionsClientAmount")
        };
    }

    private List<ReconResults> mapToReconResults(List<Tuple> results, Boolean dateGrouping) {

        Map<Object, Map<String, Map<String, List<AccountTypeResults>>>> groupedResults = results.stream()
                .filter(result -> dateGrouping ? result.get("currentDay", Timestamp.class) != null : result.get("storeId", String.class) != null)
                .collect(Collectors.groupingBy(
                        result -> dateGrouping ? result.get("currentDay", Timestamp.class) : new StoreKey(result.get("storeId", String.class),
                                result.get("merchantName", String.class)),
                        Collectors.groupingBy(
                                result -> result.get("provider", String.class) != null ? result.get("provider", String.class) : "Unknown Provider",
                                Collectors.groupingBy(
                                        result -> result.get("scheme", String.class) != null ? result.get("scheme", String.class) : "Unknown Scheme",
                                        Collectors.mapping(this::mapAccountTypeResults, Collectors.toList())
                                )
                        )
                ));
        return buildReconResults(groupedResults, dateGrouping);
    }

    private AccountTypeResults mapAccountTypeResults(Tuple result) {
        AccountTypeResults accountTypeResult = new AccountTypeResults();
        accountTypeResult.setName(result.get("accountType", String.class));

        // Initialize ReconTotals
        ReconTotals totals = new ReconTotals();

        // Total transaction counts
        totals.setTotalTransactions(result.get("totalTransactions", Long.class));
        totals.setTotalTransactionsProviderAmount(result.get("totalMatchedTransactionsProviderAmount", BigDecimal.class).longValue() + result.get("exceptionTotalMarkOffAmount", BigDecimal.class).longValue());//
        totals.setTotalTransactionsClientAmount(result.get("totalMatchedTransactionsClientAmount", BigDecimal.class).longValue() + result.get("exceptionTotalTransactionAmount", BigDecimal.class).longValue() + result.get("totalExcludedTransactionsAmount", BigDecimal.class).longValue() + result.get("totalNewTransactionsClientAmount", BigDecimal.class).longValue());

        // Total Processed Transactions
        totals.setTotalProcessedTransactions(result.get("totalTransactions", Long.class) - result.get("countNewTransactions", Long.class) - result.get("countExcludedTransactions", Long.class));
        totals.setTotalProcessedTransactionsClientAmount(result.get("totalMatchedTransactionsClientAmount", BigDecimal.class).longValue() + result.get("exceptionTotalTransactionAmount", BigDecimal.class).longValue());
        totals.setTotalProcessedTransactionsProviderAmount(result.get("totalMatchedTransactionsProviderAmount", BigDecimal.class).longValue() + result.get("exceptionTotalMarkOffAmount", BigDecimal.class).longValue());

        // Total Excluded Transactions
        totals.setTotalExcludedTransactions(result.get("countExcludedTransactions", Long.class));
        totals.setTotalExcludedTransactionsAmount(result.get("totalExcludedTransactionsAmount", BigDecimal.class).longValue());

        // Provider-only exceptions
        totals.setCountProviderOnlyExceptions(
                result.get("countProviderOnlyExceptionsStatus5", Long.class) +
                        result.get("countProviderOnlyExceptionsStatus4", Long.class) // Added aggregation for status = 4 and source = 2
        );
        totals.setTotalProviderOnlyExceptionsAmount(
                result.get("totalProviderOnlyExceptionsAmountStatus5", BigDecimal.class).longValue() +
                        result.get("totalProviderOnlyExceptionsAmountStatus4", BigDecimal.class).longValue() // Added aggregation for status = 4 and source = 2
        );
        totals.setCountClientOnlyExceptions(
                result.get("countClientOnlyExceptionsStatus4Source1", Long.class)
        );
        totals.setCountNewTransactions(
                result.get("countNewTransactions", Long.class));
        totals.setTotalNewTransactionsClientAmount(
                result.get("totalNewTransactionsClientAmount", BigDecimal.class).longValue());
        totals.setTotalClientOnlyExceptionsAmount(
                result.get("totalClientOnlyExceptionsAmountStatus4Source1", BigDecimal.class).longValue() // Added aggregation for status = 4 and source = 1
        );
        totals.setCountMatchedNotEqual(
                result.get("countMatchedNotEqual", Long.class));
        totals.setTotalMatchedNotEqualProviderAmount(
                result.get("totalMatchedNotEqualProviderAmount", BigDecimal.class).longValue());
        totals.setTotalMatchedNotEqualClientAmount(result.get("totalMatchedNotEqualClientAmount", BigDecimal.class).longValue());

        // All exceptions (statuses = 3, 4, 5)
        totals.setCountExceptions(result.get("exceptionCount", Long.class));
        totals.setTotalExceptionsProviderAmount(result.get("exceptionTotalMarkOffAmount", BigDecimal.class).longValue());
        totals.setTotalExceptionsClientAmount(result.get("exceptionTotalTransactionAmount", BigDecimal.class).longValue());

        // Matched transactions (status = 2)
        totals.setCountMatchedTransactions(result.get("matchedTransaction", Long.class));
        totals.setTotalMatchedTransactionsProviderAmount(result.get("totalMatchedTransactionsProviderAmount", BigDecimal.class).longValue());
        totals.setTotalMatchedTransactionsClientAmount(result.get("totalMatchedTransactionsClientAmount", BigDecimal.class).longValue());

        // Set the totals into AccountTypeResults
        accountTypeResult.setTotals(totals);

        return accountTypeResult;
    }

    private List<ReconResults> buildReconResults(Map<Object, Map<String, Map<String, List<AccountTypeResults>>>> groupedResults, Boolean dateGrouping) {
        return groupedResults.entrySet().stream()
                .map((entry) -> {
                    ReconResults reconResult = new ReconResults();
                    if (dateGrouping) {
                        Timestamp timestamp = (Timestamp) entry.getKey();
                        LocalDate localDate = timestamp.toLocalDateTime().toLocalDate();
                        reconResult.setDate(localDate);
                    } else {
                        if (entry.getKey() instanceof StoreKey storeKey) {
                            reconResult.setStoreName(storeKey.getStoreName());
                            reconResult.setStoreId(storeKey.getStoreId());
                        }
                    }

                    List<ProviderResults> providerResultsList = entry.getValue().entrySet().stream()
                            .map(providerEntry -> {
                                ProviderResults providerResult = new ProviderResults();
                                providerResult.setProviderName(providerEntry.getKey());

                                List<SchemaResults> schemaResultsList = providerEntry.getValue().entrySet().stream()
                                        .map(schemaEntry -> {
                                            SchemaResults schemaResult = new SchemaResults();
                                            schemaResult.setSchemaName(schemaEntry.getKey());
                                            schemaResult.setAccountTypes(schemaEntry.getValue());
                                            schemaResult.aggregateTotalsFromAccountTypes();
                                            return schemaResult;
                                        }).collect(Collectors.toList());

                                providerResult.setSchemes(schemaResultsList);
                                providerResult.aggregateTotalsFromSchema();
                                return providerResult;
                            }).collect(Collectors.toList());

                    reconResult.setProvider(providerResultsList);
                    reconResult.aggregateTotalsFromProviders();
                    return reconResult;
                }).collect(Collectors.toList());
    }

    @Override
    public List<Tuple> getMultiStoreReportData(
            List<String> storeIds, LocalDateTime fromDate, LocalDateTime toDate, List<String> terminalIds,
            List<String> transactionStatuses, List<String> transactionTypes,
            List<String> tenderTypes, List<String> accountTypes
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<TransactionEntity> transaction = query.from(TransactionEntity.class);

        query.multiselect(
                transaction.get("clientId").alias("storeId"),
                transaction.get("binCardType").alias("tenderType"),
                transaction.get("binAccountType").alias("accountType"),
                cb.count(transaction).as(Long.class).alias("totalTransactionCount"),
                cb.sum(transaction.get("clientTransactionAmount")).as(Double.class).alias("totalAmount"),
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), ReconStatus.MATCHED.getId()), 1L)
                        .otherwise(0L).as(Long.class)).alias("matchedTransaction"),
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), ReconStatus.MATCHED.getId()),
                                transaction.get("clientTransactionAmount"))
                        .otherwise(0.0).as(Double.class)).alias("matchedAmount"),
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), ReconStatus.NOT_MATCHED.getId()), 1L)
                        .otherwise(0L).as(Long.class)).alias("clientOnlyExceptionCount"),
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), ReconStatus.NOT_MATCHED.getId()),
                                transaction.get("clientTransactionAmount"))
                        .otherwise(0.0).as(Double.class)).alias("clientOnlyExceptionAmount"),
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), ReconStatus.MERCHANT_TRANSACTIONS_MISSING.getId()), 1L)
                        .otherwise(0L).as(Long.class)).alias("providerOnlyExceptionCount"),
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), ReconStatus.MERCHANT_TRANSACTIONS_MISSING.getId()),
                                transaction.get("providerMarkOffAmount"))
                        .otherwise(0.0).as(Double.class)).alias("providerOnlyExceptionAmount"),
                cb.sum(cb.selectCase()
                        .when(cb.equal(transaction.get("markoffStatus"), ReconStatus.MATCHED_NOT_EQUAL.getId()),
                                transaction.get("providerMarkOffAmount"))
                        .otherwise(0.0).as(Double.class)).alias("matchedNotEqualProviderExceptionAmount")

        );

        List<Predicate> predicates = new ArrayList<>();
        if (storeIds != null && !storeIds.isEmpty()) predicates.add(transaction.get("clientId").in(storeIds));
        if (fromDate != null && toDate != null) {
            predicates.add(
                    cb.between(transaction.get("consolidatedTransactionTimestamp"), fromDate, toDate)
            );
        }
        if (terminalIds != null && !terminalIds.isEmpty())
            predicates.add(transaction.get("terminalId").in(terminalIds));
        if (transactionStatuses != null && !transactionStatuses.isEmpty())
            predicates.add(transaction.get("markoffStatus").in(transactionStatuses));
        if (transactionTypes != null && !transactionTypes.isEmpty())
            predicates.add(transaction.get("transactionType").in(transactionTypes));
        if (tenderTypes != null && !tenderTypes.isEmpty())
            predicates.add(transaction.get("binCardType").in(tenderTypes));
        if (accountTypes != null && !accountTypes.isEmpty())
            predicates.add(transaction.get("binAccountType").in(accountTypes));

        query.where(cb.and(predicates.toArray(new Predicate[0])));
        query.groupBy(transaction.get("clientId"), transaction.get("binCardType"), transaction.get("binAccountType"));

        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public List<TransactionEntity> findPaginatedTransactions(
            LocalDateTime fromDate,
            LocalDateTime toDate,
            List<String> terminalIds,
            List<String> storeIds,
            List<String> accountTypes,
            List<String> cardSchemes,
            List<String> reconStatuses,
            List<String> transactionTypes,
            List<String> providerBanks,
            List<String> storeNames,
            List<String> accountNumbers,
            Boolean includeExceptions,
            Boolean showOnlyExceptions,
            Sort sort,
            int offset,
            int limit) {
        // This is the method that returns a paginated list of results
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TransactionEntity> query = cb.createQuery(TransactionEntity.class);
        Root<TransactionEntity> root = query.from(TransactionEntity.class);

        List<Predicate> predicates = buildPredicates(
                cb, root, fromDate, toDate, terminalIds, storeIds, accountTypes,
                cardSchemes, reconStatuses, transactionTypes, providerBanks,
                storeNames, accountNumbers, includeExceptions, showOnlyExceptions);

        query.where(cb.and(predicates.toArray(new Predicate[0])));

        if (sort != null) {
            List<Order> orders = new ArrayList<>();
            for (Sort.Order s : sort) {
                orders.add(s.isAscending() ? cb.asc(root.get(s.getProperty())) : cb.desc(root.get(s.getProperty())));
            }
            query.orderBy(orders);
        }

        // Pagination logic: set offset and limit
        return entityManager.createQuery(query)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    @Transactional(readOnly = true)
    @Override
    public Stream<TransactionEntity> findStreamTransactions(
            LocalDateTime fromDate,
            LocalDateTime toDate,
            List<String> terminalIds,
            List<String> storeIds,
            List<String> accountTypes,
            List<String> cardSchemes,
            List<String> reconStatuses,
            List<String> transactionTypes,
            List<String> providerBanks,
            List<String> storeNames,
            List<String> accountNumbers,
            Boolean includeExceptions,
            Boolean showOnlyExceptions,
            Sort sort) {
        // This method streams the results from the database without loading them all into memory
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TransactionEntity> query = cb.createQuery(TransactionEntity.class);
        Root<TransactionEntity> root = query.from(TransactionEntity.class);

        List<Predicate> predicates = buildPredicates(
                cb, root, fromDate, toDate, terminalIds, storeIds, accountTypes,
                cardSchemes, reconStatuses, transactionTypes, providerBanks,
                storeNames, accountNumbers, includeExceptions, showOnlyExceptions);

        query.where(cb.and(predicates.toArray(new Predicate[0])));

        if (sort != null) {
            List<Order> orders = new ArrayList<>();
            for (Sort.Order s : sort) {
                orders.add(s.isAscending() ? cb.asc(root.get(s.getProperty())) : cb.desc(root.get(s.getProperty())));
            }
            query.orderBy(orders);
        }

        // Streaming results directly from the database without holding all in memory
        return entityManager.createQuery(query)
                .unwrap(org.hibernate.query.Query.class)  // Hibernate-specific code to stream results
                .stream();
    }

    private List<Predicate> buildPredicates(
            CriteriaBuilder cb,
            Root<TransactionEntity> root,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            List<String> terminalIds,
            List<String> storeIds,
            List<String> accountTypes,
            List<String> cardSchemes,
            List<String> reconStatuses,
            List<String> transactionTypes,
            List<String> providerBanks,
            List<String> storeNames,
            List<String> accountNumbers,
            Boolean includeExceptions,
            Boolean showOnlyExceptions) {

        List<Predicate> predicates = new ArrayList<>();

        if (fromDate != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("consolidatedTransactionTimestamp"), fromDate));
        }
        if (toDate != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("consolidatedTransactionTimestamp"), toDate));
        }

        if (terminalIds != null && !terminalIds.isEmpty()) {
            predicates.add(cb.or(
                    root.get("providerTerminalId").in(terminalIds),
                    root.get("clientTerminalId").in(terminalIds)
            ));
        }

//            if (storeIds != null && !storeIds.isEmpty()) {
        predicates.add(root.get("clientId").in(storeIds));
        //}

        if (accountTypes != null && !accountTypes.isEmpty()) {
            predicates.add(root.get("binAccountType").in(accountTypes));
        }

        if (cardSchemes != null && !cardSchemes.isEmpty()) {
            predicates.add(root.get("binCardType").in(cardSchemes));
        }

        if (reconStatuses != null && !reconStatuses.isEmpty()) {
            predicates.add(buildMarkoffStatusPredicate(cb, root, reconStatuses, showOnlyExceptions, includeExceptions));
        } else {
            if (showOnlyExceptions) {
                predicates.add(cb.or(
                        cb.equal(root.get("markoffStatus"), 3),
                        cb.equal(root.get("markoffStatus"), 4),
                        cb.equal(root.get("markoffStatus"), 5),
                        cb.equal(root.get("markoffStatus"), 7)

                ));
            } else if (includeExceptions) {
                predicates.add(root.get("markoffStatus").in(1, 2, 3, 4, 5, 7, 8, 9));
            } else {
                predicates.add(root.get("markoffStatus").in(2, 8));
            }
        }

        if (transactionTypes != null && !transactionTypes.isEmpty()) {
            predicates.add(root.get("transactionType").in(transactionTypes));
        }

        if (providerBanks != null && !providerBanks.isEmpty()) {
            predicates.add(root.get("consolidatedBank").in(providerBanks));
        }

        if (storeNames != null && !storeNames.isEmpty()) {
            predicates.add(root.get("merchantName").in(storeNames));
        }

        if (accountNumbers != null && !accountNumbers.isEmpty()) {
            predicates.add(cb.or(
                    root.get("providerMarkoffPan").in(accountNumbers),
                    root.get("clientMaskedPan").in(accountNumbers)
            ));
        }

        return predicates;
    }

    static class StoreKey {

        private final String storeId;
        private final String storeName;

        public StoreKey(String storeId, String storeName) {
            this.storeId = storeId;
            this.storeName = storeName;
        }

        public String getStoreId() {
            return storeId;
        }

        public String getStoreName() {
            return storeName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StoreKey storeKey = (StoreKey) o;
            return Objects.equals(storeId, storeKey.storeId) && Objects.equals(storeName, storeKey.storeName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(storeId, storeName);
        }

        @Override
        public String toString() {
            return storeId + " - " + storeName;
        }
    }
    @Override
            public long countTransactions(
            LocalDateTime fromDate,
            LocalDateTime toDate,
            List<String> terminalIds,
            List<String> storeIds,
            List<String> accountTypes,
            List<String> cardSchemes,
            List<String> reconStatuses,
            List<String> transactionTypes,
            List<String> providerBanks,
            List<String> storeNames,
            List<String> accountNumbers,
            Boolean includeExceptions,
            Boolean showOnlyExceptions){
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<TransactionEntity> root = countQuery.from(TransactionEntity.class);

        List<Predicate> predicates = buildPredicates(
                cb, root, fromDate, toDate, terminalIds, storeIds, accountTypes,
                cardSchemes, reconStatuses, transactionTypes, providerBanks,
                storeNames, accountNumbers, includeExceptions, showOnlyExceptions);

        countQuery.select(cb.count(root)).where(cb.and(predicates.toArray(new Predicate[0])));
        return entityManager.createQuery(countQuery).getSingleResult();
    }
}
