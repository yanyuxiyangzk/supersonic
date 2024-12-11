package com.tencent.supersonic.headless.core.utils;

import com.tencent.supersonic.common.jsqlparser.SqlReplaceHelper;
import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import com.tencent.supersonic.common.pojo.Aggregator;
import com.tencent.supersonic.common.pojo.DateConf;
import com.tencent.supersonic.common.pojo.ItemDateResp;
import com.tencent.supersonic.common.pojo.enums.AggOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.EngineType;
import com.tencent.supersonic.common.util.DateModeUtils;
import com.tencent.supersonic.common.util.SqlFilterUtils;
import com.tencent.supersonic.common.util.StringUtil;
import com.tencent.supersonic.headless.api.pojo.Measure;
import com.tencent.supersonic.headless.api.pojo.enums.AggOption;
import com.tencent.supersonic.headless.api.pojo.enums.MetricDefineType;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.response.DimSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricSchemaResp;
import com.tencent.supersonic.headless.core.config.ExecutorConfig;
import com.tencent.supersonic.headless.core.pojo.StructQueryParam;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.tencent.supersonic.common.pojo.Constants.DAY_FORMAT;
import static com.tencent.supersonic.common.pojo.Constants.JOIN_UNDERLINE;

/** tools functions to analyze queryStructReq */
@Component
@Slf4j
public class SqlGenerateUtils {

    private final SqlFilterUtils sqlFilterUtils;

    private final DateModeUtils dateModeUtils;

    private final ExecutorConfig executorConfig;

    public SqlGenerateUtils(SqlFilterUtils sqlFilterUtils, DateModeUtils dateModeUtils,
            ExecutorConfig executorConfig) {
        this.sqlFilterUtils = sqlFilterUtils;
        this.dateModeUtils = dateModeUtils;
        this.executorConfig = executorConfig;
    }

    public static String getUnionSelect(QueryStructReq queryStructCmd) {
        StringBuilder sb = new StringBuilder();
        int locate = 0;
        for (String group : queryStructCmd.getGroups()) {
            if (group.contains(JOIN_UNDERLINE)) {
                group = group.split(JOIN_UNDERLINE)[1];
            }
            sb.append(group).append(",");
        }
        locate = 0;
        for (Aggregator agg : queryStructCmd.getAggregators()) {
            locate++;
            sb.append(agg.getColumn()).append(" as ").append("value").append(locate).append(",");
        }
        String selectSql = sb.substring(0, sb.length() - 1);
        log.debug("union select sql {}", selectSql);
        return selectSql;
    }

    public String getLimit(StructQueryParam structQueryParam) {
        if (structQueryParam != null && structQueryParam.getLimit() != null
                && structQueryParam.getLimit() > 0) {
            return " limit " + structQueryParam.getLimit();
        }
        return "";
    }

    public String getSelect(StructQueryParam structQueryParam) {
        String aggStr = structQueryParam.getAggregators().stream().map(this::getSelectField)
                .collect(Collectors.joining(","));
        return CollectionUtils.isEmpty(structQueryParam.getGroups()) ? aggStr
                : String.join(",", structQueryParam.getGroups()) + "," + aggStr;
    }

    public String getSelect(StructQueryParam structQueryParam, Map<String, String> deriveMetrics) {
        String aggStr = structQueryParam.getAggregators().stream()
                .map(a -> getSelectField(a, deriveMetrics)).collect(Collectors.joining(","));
        return CollectionUtils.isEmpty(structQueryParam.getGroups()) ? aggStr
                : String.join(",", structQueryParam.getGroups()) + "," + aggStr;
    }

    public String getSelectField(final Aggregator agg) {
        if (AggOperatorEnum.COUNT_DISTINCT.equals(agg.getFunc())) {
            return "count(distinct " + agg.getColumn() + " ) AS " + agg.getColumn() + " ";
        }
        if (CollectionUtils.isEmpty(agg.getArgs())) {
            return agg.getFunc() + "( " + agg.getColumn() + " ) AS " + agg.getColumn() + " ";
        }
        return agg.getFunc() + "( "
                + agg.getArgs().stream()
                        .map(arg -> arg.equals(agg.getColumn()) ? arg
                                : (StringUtils.isNumeric(arg) ? arg : ("'" + arg + "'")))
                        .collect(Collectors.joining(","))
                + " ) AS " + agg.getColumn() + " ";
    }

    public String getSelectField(final Aggregator agg, Map<String, String> deriveMetrics) {
        if (!deriveMetrics.containsKey(agg.getColumn())) {
            return getSelectField(agg);
        }
        return deriveMetrics.get(agg.getColumn());
    }

    public String getGroupBy(StructQueryParam structQueryParam) {
        if (CollectionUtils.isEmpty(structQueryParam.getGroups())) {
            return "";
        }
        return "group by " + String.join(",", structQueryParam.getGroups());
    }

    public String getOrderBy(StructQueryParam structQueryParam) {
        if (CollectionUtils.isEmpty(structQueryParam.getOrders())) {
            return "";
        }
        return "order by " + structQueryParam.getOrders().stream()
                .map(order -> " " + order.getColumn() + " " + order.getDirection() + " ")
                .collect(Collectors.joining(","));
    }

    public String getOrderBy(StructQueryParam structQueryParam, Map<String, String> deriveMetrics) {
        if (CollectionUtils.isEmpty(structQueryParam.getOrders())) {
            return "";
        }
        if (!structQueryParam.getOrders().stream()
                .anyMatch(o -> deriveMetrics.containsKey(o.getColumn()))) {
            return getOrderBy(structQueryParam);
        }
        return "order by " + structQueryParam.getOrders().stream()
                .map(order -> " " + (deriveMetrics.containsKey(order.getColumn())
                        ? deriveMetrics.get(order.getColumn())
                        : order.getColumn()) + " " + order.getDirection() + " ")
                .collect(Collectors.joining(","));
    }

    public String generateWhere(StructQueryParam structQueryParam, ItemDateResp itemDateResp) {
        String whereClauseFromFilter =
                sqlFilterUtils.getWhereClause(structQueryParam.getDimensionFilters());
        String whereFromDate = getDateWhereClause(structQueryParam.getDateInfo(), itemDateResp);
        return mergeDateWhereClause(structQueryParam, whereClauseFromFilter, whereFromDate);
    }

    private String mergeDateWhereClause(StructQueryParam structQueryParam,
            String whereClauseFromFilter, String whereFromDate) {
        if (StringUtils.isNotEmpty(whereFromDate)
                && StringUtils.isNotEmpty(whereClauseFromFilter)) {
            return String.format("%s AND (%s)", whereFromDate, whereClauseFromFilter);
        } else if (StringUtils.isEmpty(whereFromDate)
                && StringUtils.isNotEmpty(whereClauseFromFilter)) {
            return whereClauseFromFilter;
        } else if (StringUtils.isNotEmpty(whereFromDate)
                && StringUtils.isEmpty(whereClauseFromFilter)) {
            return whereFromDate;
        } else if (Objects.isNull(whereFromDate) && StringUtils.isEmpty(whereClauseFromFilter)) {
            log.debug("the current date information is empty, enter the date initialization logic");
            return dateModeUtils.defaultRecentDateInfo(structQueryParam.getDateInfo());
        }
        return whereClauseFromFilter;
    }

    public String getDateWhereClause(DateConf dateInfo, ItemDateResp dateDate) {
        if (Objects.isNull(dateDate) || StringUtils.isEmpty(dateDate.getStartDate())
                && StringUtils.isEmpty(dateDate.getEndDate())) {
            if (dateInfo.getDateMode().equals(DateConf.DateMode.LIST)) {
                return dateModeUtils.listDateStr(dateInfo);
            }
            if (dateInfo.getDateMode().equals(DateConf.DateMode.BETWEEN)) {
                return dateModeUtils.betweenDateStr(dateInfo);
            }
            if (dateModeUtils.hasAvailableDataMode(dateInfo)) {
                return dateModeUtils.hasDataModeStr(dateDate, dateInfo);
            }

            return dateModeUtils.defaultRecentDateInfo(dateInfo);
        }
        log.debug("dateDate:{}", dateDate);
        return dateModeUtils.getDateWhereStr(dateInfo, dateDate);
    }

    public Triple<String, String, String> getBeginEndTime(StructQueryParam structQueryParam,
            ItemDateResp dataDate) {
        if (Objects.isNull(structQueryParam.getDateInfo())) {
            return Triple.of("", "", "");
        }
        DateConf dateConf = structQueryParam.getDateInfo();
        String dateInfo = dateModeUtils.getSysDateCol(dateConf);
        if (dateInfo.isEmpty()) {
            return Triple.of("", "", "");
        }
        switch (dateConf.getDateMode()) {
            case AVAILABLE:
            case BETWEEN:
                return Triple.of(dateInfo, dateConf.getStartDate(), dateConf.getEndDate());
            case LIST:
                return Triple.of(dateInfo, Collections.min(dateConf.getDateList()),
                        Collections.max(dateConf.getDateList()));
            case RECENT:
                LocalDate dateMax = LocalDate.now().minusDays(1);
                LocalDate dateMin = dateMax.minusDays(dateConf.getUnit() - 1);
                if (Objects.isNull(dataDate)) {
                    return Triple.of(dateInfo,
                            dateMin.format(DateTimeFormatter.ofPattern(DAY_FORMAT)),
                            dateMax.format(DateTimeFormatter.ofPattern(DAY_FORMAT)));
                }
                switch (dateConf.getPeriod()) {
                    case DAY:
                        ImmutablePair<String, String> dayInfo =
                                dateModeUtils.recentDay(dataDate, dateConf);
                        return Triple.of(dateInfo, dayInfo.left, dayInfo.right);
                    case WEEK:
                        ImmutablePair<String, String> weekInfo =
                                dateModeUtils.recentWeek(dataDate, dateConf);
                        return Triple.of(dateInfo, weekInfo.left, weekInfo.right);
                    case MONTH:
                        List<ImmutablePair<String, String>> rets =
                                dateModeUtils.recentMonth(dataDate, dateConf);
                        Optional<String> minBegins =
                                rets.stream().map(i -> i.left).sorted().findFirst();
                        Optional<String> maxBegins = rets.stream().map(i -> i.right)
                                .sorted(Comparator.reverseOrder()).findFirst();
                        if (minBegins.isPresent() && maxBegins.isPresent()) {
                            return Triple.of(dateInfo, minBegins.get(), maxBegins.get());
                        }
                        break;
                    default:
                        break;
                }
                break;
            default:
                break;
        }
        return Triple.of("", "", "");
    }

    public boolean isSupportWith(EngineType engineTypeEnum, String version) {
        if (engineTypeEnum.equals(EngineType.MYSQL) && Objects.nonNull(version)
                && version.startsWith(executorConfig.getMysqlLowVersion())) {
            return false;
        }
        if (engineTypeEnum.equals(EngineType.CLICKHOUSE) && Objects.nonNull(version)
                && StringUtil.compareVersion(version, executorConfig.getCkLowVersion()) < 0) {
            return false;
        }
        return true;
    }

    public String generateDerivedMetric(final List<MetricSchemaResp> metricResps,
            final Set<String> allFields, final Map<String, Measure> allMeasures,
            final List<DimSchemaResp> dimensionResps, final String expression,
            final MetricDefineType metricDefineType, AggOption aggOption,
            Map<String, String> visitedMetric, Set<String> measures, Set<String> dimensions) {
        Set<String> fields = SqlSelectHelper.getColumnFromExpr(expression);
        if (!CollectionUtils.isEmpty(fields)) {
            Map<String, String> replace = new HashMap<>();
            for (String field : fields) {
                switch (metricDefineType) {
                    case METRIC:
                        Optional<MetricSchemaResp> metricItem = metricResps.stream()
                                .filter(m -> m.getBizName().equalsIgnoreCase(field)).findFirst();
                        if (metricItem.isPresent()) {
                            if (visitedMetric.keySet().contains(field)) {
                                replace.put(field, visitedMetric.get(field));
                                break;
                            }
                            replace.put(field,
                                    generateDerivedMetric(metricResps, allFields, allMeasures,
                                            dimensionResps, getExpr(metricItem.get()),
                                            metricItem.get().getMetricDefineType(), aggOption,
                                            visitedMetric, measures, dimensions));
                            visitedMetric.put(field, replace.get(field));
                        }
                        break;
                    case MEASURE:
                        if (allMeasures.containsKey(field)) {
                            measures.add(field);
                            replace.put(field, getExpr(allMeasures.get(field), aggOption));
                        }
                        break;
                    case FIELD:
                        if (allFields.contains(field)) {
                            Optional<DimSchemaResp> dimensionItem = dimensionResps.stream()
                                    .filter(d -> d.getBizName().equals(field)).findFirst();
                            if (dimensionItem.isPresent()) {
                                dimensions.add(field);
                            } else {
                                measures.add(field);
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
            if (!CollectionUtils.isEmpty(replace)) {
                String expr = SqlReplaceHelper.replaceExpression(expression, replace);
                log.debug("derived measure {}->{}", expression, expr);
                return expr;
            }
        }
        return expression;
    }

    public String getExpr(Measure measure, AggOption aggOption) {
        if (AggOperatorEnum.COUNT_DISTINCT.getOperator().equalsIgnoreCase(measure.getAgg())) {
            return AggOption.NATIVE.equals(aggOption) ? measure.getBizName()
                    : AggOperatorEnum.COUNT.getOperator() + " ( " + AggOperatorEnum.DISTINCT + " "
                            + measure.getBizName() + " ) ";
        }
        return AggOption.NATIVE.equals(aggOption) ? measure.getBizName()
                : measure.getAgg() + " ( " + measure.getBizName() + " ) ";
    }

    public String getExpr(MetricResp metricResp) {
        if (Objects.isNull(metricResp.getMetricDefineType())) {
            return metricResp.getMetricDefineByMeasureParams().getExpr();
        }
        if (metricResp.getMetricDefineType().equals(MetricDefineType.METRIC)) {
            return metricResp.getMetricDefineByMetricParams().getExpr();
        }
        if (metricResp.getMetricDefineType().equals(MetricDefineType.FIELD)) {
            return metricResp.getMetricDefineByFieldParams().getExpr();
        }
        // measure add agg function
        return metricResp.getMetricDefineByMeasureParams().getExpr();
    }
}
