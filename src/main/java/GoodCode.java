public class GoodCode {

    public RefundResponse refund(RefundRequest refundRequest, boolean returnWrapper) throws Exception {
        UUID transactionId = null;
        RefundLegEntity refundLeg = null;
        RefundResponse refundResponse = null;
        BulkTransactionResponse bulkTransactionResponse = null;
        ProfileResponse profileResponse = null;
        CardReq cardReq = new CardReq();
        boolean reconRef = false;
        UUID reconRefUUID = null;
        Double amount = null;
        Integer requestedamount = cardReq.getAmount();
        Double bankedAmount = null;
        CardRsp switchCardResponse = null;
        try {
            transactionId = refundRequest.getTransactionId();

            Optional<TransactionEntity> currentTransactionEntityQuery;
            TransactionEntity modifiedTransactionEntity;
            if ((transactionId != null) &&
                    (currentTransactionEntityQuery = transactionRepository.findByTransactionId(transactionId)).isPresent()) {
                modifiedTransactionEntity = currentTransactionEntityQuery.get();
            } else {
//                throw new ApplicationException(HttpStatus.NOT_FOUND,
//                        new ErrorCode(TRANSACTION_NOT_FOUND),
//                        "Transaction not found: transactionId: " + transactionId);
                if ((transactionId != null) &&
                        (currentTransactionEntityQuery = transactionRepository.findByReconReference(String.valueOf(transactionId))).isPresent()) {
                    modifiedTransactionEntity = currentTransactionEntityQuery.get();
                    transactionId = modifiedTransactionEntity.getTransactionId();
                } else {
                    if ((transactionId != null) &&
                            (currentTransactionEntityQuery = transactionRepository.findByReconReference(String.valueOf(transactionId))).isPresent()) {
                        reconRefUUID = transactionId;
                        log.log(Level.INFO, "Transaction lookup ReconRef");
                        reconRef = true;
                        modifiedTransactionEntity = currentTransactionEntityQuery.get();
                        modifiedTransactionEntity.setReconReference(String.valueOf(modifiedTransactionEntity.getTransactionId()));
                        transactionId = modifiedTransactionEntity.getTransactionId();

                    } else {
                        throw new ApplicationException(HttpStatus.NOT_FOUND,
                                new ErrorCode(TRANSACTION_NOT_FOUND),
                                "Transaction not found: transactionId: " + transactionId);
                    }
                }

                try {

                    if (transactionRepository.findByTransactionId(transactionId).get().getReconReference() != null) {
                        log.log(Level.INFO, "ReconRef Present");
                        reconRef = true;
                        reconRefUUID = UUID.fromString(transactionRepository.findByTransactionId(transactionId).get().getReconReference());
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            if (!refundRequest.getMerchantId().toString().contentEquals(modifiedTransactionEntity.getMerchantId().toString())) {
                throw new ApplicationException(HttpStatus.PRECONDITION_FAILED,
                        new ErrorCode(MERCHANT_ID_MISMATCH),
                        "MerchantId mismatch for transactionId: " + transactionId +
                                " found: " + refundRequest.getMerchantId() +
                                " expected: " + modifiedTransactionEntity.getMerchantId());
            }
            if (!refundRequest.getProfileId().toString().contentEquals(modifiedTransactionEntity.getProfileId().toString())) {
                throw new ApplicationException(HttpStatus.PRECONDITION_FAILED,
                        new ErrorCode(MERCHANT_PROFILE_ID_MISMATCH),
                        "Merchant ProfileId mismatch for transactionId: " + transactionId +
                                " found: " + refundRequest.getProfileId() + " expected: " + modifiedTransactionEntity.getProfileId());
            }
            TransactionEntity transaction = modifiedTransactionEntity;

            profileResponse = eComGateWayProvider.getMerchantProfile(refundRequest.getProfileId(), transactionId);

            if (!(
                    (transaction.getTransactionStatus() == TransactionStatus.SETTLED) ||
                            (transaction.getTransactionStatus() == TransactionStatus.PARTIALLY_REFUNDED)
            )) {
                throw new ApplicationException(new ErrorCode(INCORRECT_STATUS_TO_REFUND),
                        "Can't refund transactionId: " + transactionId +
                                " it is not in status SETTLED or PARTIALLY_REFUNDED, but " + transaction.getTransactionStatus());
            }

            if ("CYBERSOURCE".equalsIgnoreCase(transaction.getThreeDsProvider())) {
                var settlementLeg = settlementLegRepository.findByTransactionTransactionId(transaction.getTransactionId());
                boolean isWithin24Hours = settlementLeg.map(leg ->
                        Duration.between(leg.getCreationDateTime(), LocalDateTime.now()).toHours() < 24
                ).orElseGet(() ->
                        Duration.between(transaction.getCreationDateTime(), LocalDateTime.now()).toHours() < 24
                );

                if (isWithin24Hours) {
                    throw new ApplicationException(HttpStatus.PRECONDITION_FAILED,
                            new ErrorCode(CYBERSOURCE_REFUND_BEFORE_24HOURS),
                            "Cybersource transaction could not be refunded, wait 24 hours after Settlement before attempting to refund for transaction: " + transactionId);
                }
            }

            Optional<AuthorisationLegEntity> authorisationLegQuery = authorisationLegRepository.findByTransactionTransactionId(transactionId);
            if (authorisationLegQuery.isEmpty()) {
                throw new ApplicationException(HttpStatus.NOT_FOUND,
                        new ErrorCode(TRANSACTION_AUTH_LEG_NOT_FOUND),
                        "Authorisation leg not found for transactionId: " + transactionId);
            }
            AuthorisationLegEntity authorisationLeg = authorisationLegQuery.get();

            RefundLegEntity modifiedRefundLegEntity;

            modifiedRefundLegEntity = new RefundLegEntity();
            modifiedRefundLegEntity.setTransaction(transaction);
            modifiedRefundLegEntity.setRefundLegId(UUID.randomUUID());
            modifiedRefundLegEntity.setSequence(generateSequenceNumber(transactionId));
            modifiedRefundLegEntity.setRequestedMajorUnitAmount(refundRequest.getAmount());
            modifiedRefundLegEntity.setRrn(transaction.getRrn());
            refundLegRepository.save(modifiedRefundLegEntity);
            refundLeg = modifiedRefundLegEntity;


            // Now start building up the Card Request for the switch:


            // Map all the information that needs no external dependencies and are constant
            // or come with the request.
            cardReq = setDateInfo(cardReq);
            cardReq = setAccountTypeInfo(cardReq);
            cardReq.cardPresentment("00000");
            profileResponse = eComGateWayProvider.getMerchantProfile(refundRequest.getProfileId(), transactionId);

            // Default Transactions is Offline
            // i.e. a TranType: 20 with TRANTYPE 8604
            // Add transqualifier for Cybersource
            if (profileResponse.getThreeDsProvider().equalsIgnoreCase("BANK_SERVE")) {
                cardReq.tran(Tran._RETURN)
                        .tranQualifier(TRAN_QUALIFIER_REFUND);
                log.log(Level.INFO, "TRAN_QUALIFIER_REFUND Bankserv " + TRAN_QUALIFIER_REFUND);
            } else if (profileResponse.getThreeDsProvider().equalsIgnoreCase("CYBERSOURCE")) {
                cardReq.tran(Tran._RETURN)
                        .tranQualifier(TRAN_QUALIFIER_REFUND_CYBERSOURCE);
                log.log(Level.INFO, "TRAN_QUALIFIER_REFUND Cybersource " + TRAN_QUALIFIER_REFUND_CYBERSOURCE);
            }
            // But for some schemes we use Online when configured to do so
            Optional<MappingConfigEntity> mappingConfigQuery = mappingConfigRepository
                    .findByConfigurationPointAndScheme_name(MAPPING_CONFIG_POINT_RETURN_ONLINE_OFFLINE,
                            authorisationLeg.getScheme().getName());
            if (mappingConfigQuery.isPresent()) {
                MappingConfigEntity mappingConfig = mappingConfigQuery.get();
                if (mappingConfig.getRefundConfiguration() == RefundConfiguration.OFFLINE_REFUND) {
                    // Add transqualifier for Cybersource
                    if (profileResponse.getThreeDsProvider().equalsIgnoreCase("BANK_SERVE")) {
                        cardReq.tran(Tran._RETURN)
                                .tranQualifier(TRAN_QUALIFIER_REFUND);
                        log.log(Level.INFO, "TRAN_QUALIFIER_REFUND Bankserv " + TRAN_QUALIFIER_REFUND);
                    } else if (profileResponse.getThreeDsProvider().equalsIgnoreCase("CYBERSOURCE")) {
                        cardReq.tran(Tran._RETURN)
                                .tranQualifier(TRAN_QUALIFIER_REFUND_CYBERSOURCE);
                        log.log(Level.INFO, "TRAN_QUALIFIER_REFUND Cybersource " + TRAN_QUALIFIER_REFUND_CYBERSOURCE);
                    }

                }
                if (mappingConfig.getRefundConfiguration() == RefundConfiguration.ONLINE_REFUND) {
                    cardReq.tran(Tran._RETURN)
                            .tranQualifier(TRAN_QUALIFIER_AUTHORISE_DMS);
                }
            }

            // Setup Tracking and tracing IDs

            if (reconRef) {
                cardReq.reference(String.valueOf(reconRefUUID));
            } else {
                cardReq.reference(refundLeg.getRefundLegId().toString());
            }

            cardReq.seq(refundLeg.getSequence());
            cardReq.rrn(ImbekoMappingHelper.generateRrn());
            cardReq.echo(transaction.getTransactionId().toString());
            cardReq.setSchemeToken(profileResponse.isSchemeTokenizationEnabled());
            cardReq.cardToken(authorisationLeg.getToken());

            cardReq.route(authorisationLeg.getRoute().getCode())
                    .store(authorisationLeg.getStoreId())
                    .pos(authorisationLeg.getTerminalId())
                    .mcc(authorisationLeg.getMcc());
            boolean autoSettleFlag = authorisationLeg.getAutoSettleFlag() != null ? authorisationLeg.getAutoSettleFlag() : false;


            // Get the merchant info for the merchant->profile, flow and scheme
            // use it to set the store detail
            Merchant merchant = merchantInfoProvider.getMerchantInfo(refundRequest.getMerchantId(), transactionId);
            ImbekoApiKey imbekoApiKey = merchantInfoProvider.getApiKey(refundRequest.getMerchantId());


            TransactionResponse transactionResponse;
            try {
                transactionResponse = eComGateWayProvider.getTransactionInfo(transactionId);


            } catch (Exception e) {
                log.log(Level.ERROR, transactionId + ": Error getting EComGatewayResponse for transactionId: " + transactionId);
                throw new ApplicationException(HttpStatus.NOT_FOUND, new ErrorCode(TRANSACTION_NOT_FOUND), "Error getting EComGatewayResponse for transactionId: " + transactionId);
            }

            if ((transactionResponse.getAggregator() != null) && (transactionResponse.getAggregator().size() > 0)) {
                cardReq.setAggregator(mapAggregatorInfo(transactionResponse.getAggregator()));
            }

            Optional<ProfileStoreDetailsEntity> profileStoreDetailsEntity = profileStoreDetailsRepository.findByProfileId(refundRequest.getProfileId());

            if (profileStoreDetailsEntity.isPresent() && profileStoreDetailsEntity.get().isActive()) { // Get from Profile Store Details override
                log.log(Level.INFO, transactionId + ": Store Details being overridden for profile id : " + refundRequest.getProfileId());
                cardReq.setStoreDetails(mapMerchantInfo(profileStoreDetailsEntity.get()));
            } else if ((transactionResponse.getStoreDetails() != null) && (transactionResponse.getStoreDetails().size() > 0)
                    // This is a workaround to fix IPG-1199 - Transaction fails when only including the Aggregator details but not the Store Details for both Direct and Payment Setup/Key wrappers
                    // ecomgw is returning something incomplete like the following:
                    /// 2023-05-16 16:12:36.932  INFO 1 --- [nio-8081-exec-8] z.c.t.i.c.s.CardNotPresentServiceImpl    : StoreDetails Provided : [class StoreDetails{
                    ///     name:
                    ///     city:
                    ///     region: null
                    ///     country: ZA
                    ///     postalCode: null
                    ///     streetAddress: null
                    ///     customerServicePhoneNumber: 27781714038
                    /// }]
                    // This is the smallest impact fix just before a release during regression
                    // Ticket to fix in ecom and remove this workaround: IPG-1209
                    && (transactionResponse.getStoreDetails().get(0).getRegion() != null)
                    && (transactionResponse.getStoreDetails().get(0).getPostalCode() != null)
                    && (transactionResponse.getStoreDetails().get(0).getStreetAddress() != null)) {
                cardReq.setStoreDetails(mapStoreDetailsInfo(transactionResponse.getStoreDetails()));
            } else {
                cardReq.setStoreDetails(mapMerchantInfo(merchant));
            }


            String transactionCurrencyCode = Currency.getInstance(
                    transaction.getIsoCurrencyCode()).getNumericCodeAsString();

            if (!transactionCurrencyCode.contentEquals(authorisationLeg.getNumericIsoCurrencyCode())) {
                throw new ApplicationException(HttpStatus.PRECONDITION_FAILED,
                        new ErrorCode(CURRENCY_MISMATCH),
                        "Refund attempted for currency " + transactionCurrencyCode +
                                " but the original currency was " +
                                authorisationLeg.getNumericIsoCurrencyCode() + "for transactionId: " + transactionId);
            }


            this.setFinancialTransactionInfo(cardReq, refundRequest.getAmount() == null ? 0.0f : refundRequest.getAmount(), transaction.getIsoCurrencyCode(), transactionId);

            boolean partialFlag = true;
            int adjustmentSumAmount = adjustmentLegRepository.sumAdjustmentAmountByTransactionId(transactionId);

            Integer originalAmount = authorisationLeg.getAmount();
            originalAmount += adjustmentSumAmount;

            amount = (double) ImbekoMappingHelper.getFractionalCurrencyAmount(originalAmount, transaction.getIsoCurrencyCode());
            Integer summedRefundsSoFar = refundLegRepository.sumRefundAmountByTransactionId(transactionId);
            summedRefundsSoFar = summedRefundsSoFar == null ? 0 : summedRefundsSoFar;
            Integer thisAmount = cardReq.getAmount();
            requestedamount = thisAmount;
            if (refundRequest.getAmount() == null) {
                cardReq.setAmount(originalAmount - summedRefundsSoFar);
            } else {
                cardReq.setAmount(cardReq.getAmount());
            }

            if (summedRefundsSoFar + thisAmount > originalAmount) {
                throw new ApplicationException(HttpStatus.PRECONDITION_FAILED,
                        new ErrorCode(REFUND_ATTEMPTED_FOR_AMOUNT_LARGER_THAN_ORIGINALLY_AUTHORISED),
                        "Refund attempted for more than the original amount for transactionId: " + transactionId);
            }
            if (summedRefundsSoFar + thisAmount == originalAmount) {
                partialFlag = false;
            }

            ArrayList dataList = new ArrayList();
            DataItem data = null;
            if (transaction.getIncrementalAuthFlag()) {
                data = new DataItem();
                data.setKey("FINAL_AUTH_IND");
                data.setValue("2");
                dataList.add(data);

                data = new DataItem();
                data.setKey("IncAuth ");
                data.setValue("I");
                dataList.add(data);

                cardReq.setData(dataList);
            }

            if (transaction.getSchemeTraceId() != null && !transaction.getSchemeTraceId().isEmpty()) {
                data = new DataItem();
                data.setKey("Scheme_Trace_ID ");
                data.setValue(transaction.getSchemeTraceId());
                dataList.add(data);

                cardReq.setData(dataList);
            }

            if (transaction.getCyberSourceTransactionId() != null && !transaction.getCyberSourceTransactionId().isBlank()) {
                data = new DataItem();
                data.setKey("CyberSourceId");
                data.setValue(transaction.getCyberSourceTransactionId());
                dataList.add(data);

                cardReq.setData(dataList);
            }

            // Set amount for transaction before calling (in case it fails)
            try {
                refundLeg.setAmount(cardReq.getAmount());
                refundLegRepository.save(refundLeg);
            } catch (Exception e) {
                log.log(Level.ERROR, transactionId + ": Error saving amount to RefundLeg DB table on refund." + e.getMessage());
            }

            //NOTE: currently the settled amount always match the original amount, but in future
            //      when partial settlement are allowed this will need to be amended.
            bankedAmount = (double) ImbekoMappingHelper.getFractionalCurrencyAmount(originalAmount - summedRefundsSoFar - thisAmount, transaction.getIsoCurrencyCode());

            String refundReq = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cardReq);
            refundLeg.setImbekoRequest(refundReq);
            switchCardResponse = imbekoService.performCardTransaction(cardReq, transactionId, imbekoApiKey.getApiKey());

            refundLeg.setAuthcode(switchCardResponse.getAuthCode());
            refundLeg.setResponseCode(switchCardResponse.getResponseCode());
            refundLeg.setResponseText(switchCardResponse.getResponseText());
            refundLeg.setToken(switchCardResponse.getCardToken());
            refundLeg.setDmsIndicator(switchCardResponse.getDmsIndicator());
            if (switchCardResponse.getAmount() != null) {
                refundLeg.setAmount(switchCardResponse.getAmount());
                refundLeg.setNumericIsoCurrencyCode(switchCardResponse.getCurrency());
                refundLeg.setStatus(TransactionLegStatus.INITIATED);
                refundLeg.setImbekoResponse(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(switchCardResponse));
                refundLegRepository.save(refundLeg);
            } else {
                refundLeg.setAmount(requestedamount);
                refundLeg.setNumericIsoCurrencyCode(switchCardResponse.getCurrency());
                refundLeg.setStatus(TransactionLegStatus.FAILED);
                refundLeg.setImbekoResponse(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(switchCardResponse));
                refundLegRepository.save(refundLeg);
            }

            // we only update the transaction status on success:
            TransactionStatus transactionStatus;
            if (switchCardResponse.getResponseCode().contentEquals("00")) {
                if (partialFlag) {
                    transactionStatus = TransactionStatus.PARTIALLY_REFUNDED;
                } else {
                    transactionStatus = TransactionStatus.REFUNDED;
                }
                transaction.setTransactionStatus(transactionStatus);
                transactionRepository.save(transaction);
            } else if (switchCardResponse.getResponseCode().contentEquals("09")
                    && "CYBERSOURCE".equalsIgnoreCase(transaction.getThreeDsProvider())) {
                if (partialFlag) {
                    transactionStatus = TransactionStatus.PARTIALLY_REFUNDED;
                } else {
                    transactionStatus = TransactionStatus.REFUNDED;
                }
                transaction.setTransactionStatus(transactionStatus);
                transactionRepository.save(transaction);
            } else {
                transactionStatus = TransactionStatus.FAILED;
            }

            // TJ card API response mappings
            refundResponse =
                    new RefundResponse(refundRequest.getTransactionId(),
                            transactionStatus,
                            switchCardResponse.getResponseCode(),
                            lookupResponseCodeMeaning(switchCardResponse.getResponseCode()),
                            switchCardResponse.getResponseText(),
                            lookupResponseCodeRecommendedText(switchCardResponse.getResponseCode()),
                            switchCardResponse.getRrn()

                    );
            if (returnWrapper) {
                refundResponse = new WrapperRefundResponse(refundResponse, switchCardResponse);
            }
            if (refundResponse.getProviderResponseCode().contentEquals("00")) {
                refundLeg.setStatus(TransactionLegStatus.COMPLETED);
            } else {
                refundLeg.setStatus(TransactionLegStatus.FAILED);
                refundLeg.setAmount(requestedamount);
            }
            modifiedRefundLegEntity.setRrn(cardReq.getRrn());
            refundLegRepository.save(refundLeg);

            //Add Jms messanger to call refundEvent
            if (returnWrapper) {
                WrapperRefundResponse wrapperRefundResponse = new WrapperRefundResponse(refundResponse, switchCardResponse);
                try {
                    if (wrapperRefundResponse != null) {
                        log.log(Level.INFO, transactionId + ": Sending message to transactionStatusEventQueue. With Queue name >>" + transactionStatusEventQueue + "<<.");
                        WrapperRefundEvent event = new WrapperRefundEvent(wrapperRefundResponse, refundRequest.getAmount(), bankedAmount);
                        if (transactionStatus == TransactionStatus.REFUNDED || transactionStatus == TransactionStatus.PARTIALLY_REFUNDED)
                            jmsTemplate.convertAndSend(transactionStatusEventQueue, event, (var m) -> {
                                m.setLongProperty(ScheduledMessage.AMQ_SCHEDULED_DELAY, JMS_DELAY);
                                return m;
                            });
                    }
                } catch (Exception ex) {
                    log.log(Level.ERROR, transactionId + ": Error sending message to transactionStatusEventQueue " + ex.getMessage());
                    // Failure to send event should not fail the transaction, so we continue.
                }
                return refundResponse;
            } else {
                try {
                    if (refundResponse != null) {
                        log.log(Level.INFO, transactionId + ": Sending message to transactionStatusEventQueue. With Queue name >>" + transactionStatusEventQueue + "<<.");
                        RefundEvent event = new RefundEvent(refundResponse, refundRequest.getAmount(), bankedAmount);
                        if (transactionStatus == TransactionStatus.REFUNDED || transactionStatus == TransactionStatus.PARTIALLY_REFUNDED)
                            jmsTemplate.convertAndSend(transactionStatusEventQueue, event, (var m) -> {
                                m.setLongProperty(ScheduledMessage.AMQ_SCHEDULED_DELAY, JMS_DELAY);
                                return m;
                            });
                    }
                } catch (Exception ex) {
                    log.log(Level.ERROR, transactionId + ": Error sending message to transactionStatusEventQueue " + ex.getMessage());
                    // Failure to send event should not fail the transaction, so we continue.
                }
                return refundResponse;
            }


        } catch (Exception ex) {
            log.log(Level.ERROR, transactionId + ": Error performing refund " + ex.getMessage());
            try {
                refundLeg.setAmount(cardReq.getAmount());
                refundLegRepository.save(refundLeg);
            } catch (Exception e) {
                log.log(Level.ERROR, transactionId + ": Error saving amount to RefundLeg DB table on refund." + e.getMessage());
            }


            if (refundLeg != null) {
                if (ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 255)).contains("timed out")) {
                    refundLeg.setResponseCode("99");
                    refundLeg.setResponseText("Connection timed out to switch");
                } else {
                    refundLeg.setResponseCode("98");
                    String responseText = "Unknown issue";
                    if (ex.getMessage().lastIndexOf(":") != -1) {
//                        responseText = ex.getMessage().substring(ex.getMessage().lastIndexOf("") + 1, ex.getMessage().length() - 2);
                        responseText = ex.getMessage().substring(ex.getMessage().indexOf('"') + 1, ex.getMessage().length() - 1);
                    } else if (StringUtils.substringBetween(ex.getMessage(), "\"", "\"") != null) {
                        responseText = StringUtils.substringBetween(ex.getMessage(), "\"", "\"");
                    }
                    refundLeg.setResponseText(responseText);
                }

                refundLeg.setStatus(TransactionLegStatus.FAILED);

                refundLegRepository.save(refundLeg);
            }
            throw ex;
        }


    }

}
