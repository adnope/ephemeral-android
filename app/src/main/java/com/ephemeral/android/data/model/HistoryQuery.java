package com.ephemeral.android.data.model;

public final class HistoryQuery {
    private final long cursor;
    private final ItemTypeFilter typeFilter;
    private final String query;
    private final boolean searchBody;
    private final String dateFromIso;
    private final String dateToIso;
    private final RecentFilter recent;
    private final VisibilityFilter visibility;

    public HistoryQuery(long cursor, ItemTypeFilter typeFilter, String query, boolean searchBody,
            String dateFromIso, String dateToIso, RecentFilter recent) {
        this(cursor, typeFilter, query, searchBody, dateFromIso, dateToIso, recent, VisibilityFilter.ALL);
    }

    public HistoryQuery(long cursor, ItemTypeFilter typeFilter, String query, boolean searchBody,
            String dateFromIso, String dateToIso, RecentFilter recent, VisibilityFilter visibility) {
        this.cursor = Math.max(0, cursor);
        this.typeFilter = typeFilter == null ? ItemTypeFilter.ALL : typeFilter;
        this.query = query == null ? "" : query.trim();
        this.searchBody = searchBody;
        this.dateFromIso = dateFromIso == null ? "" : dateFromIso.trim();
        this.dateToIso = dateToIso == null ? "" : dateToIso.trim();
        this.recent = recent == null ? RecentFilter.ANY_TIME : recent;
        this.visibility = visibility == null ? VisibilityFilter.ALL : visibility;
    }

    public static HistoryQuery empty() {
        return new HistoryQuery(0, ItemTypeFilter.ALL, "", false, "", "", RecentFilter.ANY_TIME, VisibilityFilter.ALL);
    }

    public HistoryQuery withCursor(long nextCursor) {
        return new HistoryQuery(nextCursor, typeFilter, query, searchBody, dateFromIso, dateToIso, recent, visibility);
    }

    public HistoryQuery withVisibility(VisibilityFilter nextVisibility) {
        return new HistoryQuery(cursor, typeFilter, query, searchBody, dateFromIso, dateToIso, recent, nextVisibility);
    }

    public HistoryQuery clearSearchPreservingType() {
        return new HistoryQuery(0, typeFilter, "", false, "", "", RecentFilter.ANY_TIME, VisibilityFilter.ALL);
    }

    public long getCursor() {
        return cursor;
    }

    public ItemTypeFilter getTypeFilter() {
        return typeFilter;
    }

    public String getQuery() {
        return query;
    }

    public boolean isSearchBody() {
        return searchBody;
    }

    public String getDateFromIso() {
        return dateFromIso;
    }

    public String getDateToIso() {
        return dateToIso;
    }

    public RecentFilter getRecent() {
        return recent;
    }

    public VisibilityFilter getVisibility() {
        return visibility;
    }
}
