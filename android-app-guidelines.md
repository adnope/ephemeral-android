# Android App Guidelines

These guidelines are for an AI agent building a native Android frontend for Ephemeral from an empty directory.

The goal is a small, fast, native Android app: Java source, XML layouts, platform widgets, minimal dependencies, no cross-platform runtime, and no Jetpack Compose.

Do not document or hardcode backend endpoint paths in this Android project yet. The mobile API contract is intentionally deferred until the backend exposes all required JSON/mobile endpoints.

This file must be sufficient context for an implementation agent that has no access to the current web frontend source.

## Ephemeral Context

Ephemeral is a lightweight self-hosted app for quickly sharing text messages and files across devices.

The existing web app has three user-facing areas:

- login/setup,
- chat,
- history.

The backend stores every message and upload as an `Item`. Items are shown newest-first. Lists use cursor pagination based on the last visible item ID. File uploads are stored by the backend. Images and videos may receive metadata asynchronously after upload, so the client must handle later item updates.

The app is not a general messenger. There are no contacts, channels, read receipts, reactions, push notifications, or multi-user chat rooms. The UI should stay compact and utility-focused.

## Core Domain Concepts

Item types:

- `text`: a text message created from the chat composer.
- `image`: an uploaded file classified as an image.
- `video`: an uploaded file classified as a video.
- `file`: any other uploaded file.

An item has:

- stable numeric ID,
- type,
- content reference,
- original filename for uploads,
- file size in bytes for uploads,
- metadata,
- creation time.

Metadata has:

- image/video width,
- image/video height,
- video duration when available,
- MIME type,
- thumbnail reference for generated video thumbnails.

Item events:

- `item:new`: a new text message or uploaded file exists.
- `item:updated`: an existing item received updated metadata or thumbnail.
- `item:deleted`: an item was deleted permanently.

The mobile app must treat these events as invalidation signals. It may refresh visible data instead of applying partial updates when that is simpler and safer.

## Current Web Behavior To Preserve

Login/setup:

- If no user exists, the login form creates the first account.
- Otherwise, the same form signs in.
- The login form has username and password fields.
- The password field has a reveal/hide toggle.
- Failed login/setup shows an inline error.

Chat:

- The header shows the app name `Ephemeral`.
- Primary navigation has Chat and History.
- Logout is available from the authenticated UI.
- Messages appear newest-first.
- Older messages load when the user reaches the list boundary.
- Text messages are sent from a multiline composer.
- Empty text after trimming is not sent.
- Hardware keyboard behavior: Enter sends, Shift+Enter inserts a newline.
- Sent text appears optimistically before the backend confirms it.
- Optimistic status values are sending, sent, failed.
- Failed optimistic text has a Retry action.
- Files can be attached with a picker.
- Multiple files can be queued.
- Uploads have a visible queue with per-file and aggregate progress.
- Upload queue can be collapsed, closed, cleared, retried, and canceled.
- Items can be deleted from an overflow menu.

History:

- History is a searchable archive of uploaded and text items.
- It supports query search, type filters, date filters, recent filters, and body search for text/code files.
- Type filters are all, images, videos, files.
- Recent filters are any time, last day, last 7 days, last 14 days, last 30 days, last 90 days, last 6 months, last year.
- Date filters are from date and to date.
- Search clear resets query/date/recent/body filters while preserving the active type filter.
- Results load more items when the user reaches the list boundary.

Media:

- Images open in a full-screen viewer.
- Videos open in a full-screen viewer with playback controls.
- Video items use a generated thumbnail when available and a placeholder otherwise.
- The viewer supports previous/next navigation within the current list.
- The viewer shows filename, file size, dimensions when available, and creation time.
- The viewer supports download, delete, and close.

Generic files:

- Generic file rows show filename and size.
- They support View and Download.
- View opens the text/code preview only when the backend says the file is previewable.
- If a file is too large or unsupported for preview, the app should fall back to download/open-with where appropriate.

Text/code preview:

- Shows title, metadata, syntax selector, render status, content, copy, download, delete, and close.
- Auto-detects language from backend-provided language when available.
- Allows manual language selection.
- Falls back to plain text if highlighting fails.

## Required Mobile API Capabilities

Do not define endpoint paths here. The backend API contract is deferred. The Android code must use a single adapter that maps these capabilities to actual endpoints later.

The future mobile API must provide these capabilities:

- determine whether the server is in setup mode or login mode;
- create the first account;
- authenticate with username and password;
- logout and invalidate the current session;
- restore or validate an existing session;
- load one chat page with optional cursor;
- create a text item;
- upload one file as a streaming multipart or binary request;
- delete one item;
- load one history page with filters and optional cursor;
- load a bounded text/code preview for a file item;
- stream or poll item events;
- download original files and generated thumbnails;
- expose server-side runtime limits relevant to the UI, including page sizes, max upload size, text preview max size, and upload concurrency.

The Android app must define the API as capability methods, not URL methods. Endpoint path strings must be isolated to one implementation file when the contract is later added.

## Canonical App States

Top-level app states:

- unknown session: app is starting and checking stored session;
- unauthenticated setup: server requires first account creation;
- unauthenticated login: server has an account and requires login;
- authenticated chat;
- authenticated history;
- media viewer open;
- text preview open;
- session expired.

Authentication failures during normal API calls must transition to session expired, clear local session state, and show the login screen.

## Runtime Configuration

The backend exposes configurable runtime limits. The mobile app must not duplicate these as hidden constants once the mobile API supports configuration.

Known backend values:

- chat page size,
- history page size,
- search result limit,
- max upload size,
- text preview max,
- body index max,
- media worker count,
- upload concurrency.

The Android app directly needs:

- chat page size for paging expectations;
- history page size for paging expectations;
- max upload size for preflight validation;
- text preview max for UI messaging;
- upload concurrency for the upload queue.

If config is unavailable during early scaffolding, keep conservative local defaults in a single debug-only configuration class and mark the release API integration as incomplete.

## Product Scope

Build a native Android client that provides every user-facing feature currently available in the web frontend:

- First-run account setup.
- Username/password login.
- Password visibility toggle on the login form.
- Login error display.
- Authenticated session persistence.
- Logout.
- Main chat screen.
- History/gallery screen.
- Top-level navigation between Chat and History.
- Real-time item updates.
- Text message composer.
- Multiline message input.
- Empty-message prevention after trimming whitespace.
- Optimistic text-message rendering with sending, sent, failed, and retry states.
- Reverse chronological chat feed.
- Cursor-based loading of older chat items.
- File picker with multi-select upload.
- Android share-intent file intake for uploading files from other apps.
- Upload queue with per-file progress.
- Overall upload progress.
- Upload statuses: queued, uploading, done, failed, canceled.
- Upload cancel.
- Upload retry.
- Clear completed uploads.
- Collapsible upload queue.
- Close upload queue when no active uploads remain.
- Text item rendering.
- Image item rendering with filename, size, timestamp, and preview.
- Video item rendering with thumbnail when available, fallback placeholder, filename, size, timestamp, and preview.
- Generic file item rendering with filename, size, view, and download actions.
- Per-item overflow menu.
- Permanent item deletion with confirmation.
- Real-time removal of deleted items from visible lists.
- Refresh of visible items when media metadata or thumbnails are updated.
- Full-screen image viewer.
- Full-screen video viewer with native playback controls.
- Media viewer actions: download, delete, close.
- Media viewer navigation: previous and next within the current list.
- Media metadata display: file size, dimensions when available, and creation time.
- History search by filename/query.
- History clear-search action.
- History filters by type: all, images, videos, files.
- History date filters: from date and to date.
- History recent filters: any time, last day, last 7 days, last 14 days, last 30 days, last 90 days, last 6 months, last year.
- History option to search text/code file bodies.
- History gallery/grid layout.
- Cursor-based loading of more history results.
- File preview viewer for text/code files.
- Text preview loading state.
- Text preview error state.
- Text preview metadata: filename, size, MIME type, created time.
- Text preview actions: copy, download, delete, close.
- Syntax/language selector with auto-detect and manual language choices.
- Syntax render status.
- Plain-text fallback when highlighting fails or a language is unsupported.

## Non-Goals

- Do not use Jetpack Compose.
- Do not use Flutter.
- Do not use React Native.
- Do not build the UI with WebView.
- Do not scrape server-rendered HTML.
- Do not add social login, biometrics, push notifications, background sync, or multi-server account management unless explicitly requested later.
- Do not introduce endpoint documentation or endpoint path constants until the mobile API contract is finalized.

## Technical Direction

Use the classic lightweight Android model:

- Language: Java.
- UI: XML layouts, styles, drawables, and native Android views.
- Architecture: one small Android application module.
- Minimum SDK: 26 unless the project owner chooses otherwise.
- Target SDK and compile SDK: latest stable Android SDK available at implementation time.
- Release output: Android App Bundle for distribution and signed release APK for size verification.

Prefer framework APIs and small AndroidX components. Avoid dependency-heavy abstractions.

Allowed dependencies by default:

- OkHttp for HTTP, cookies, streaming, uploads, and event streams.
- AndroidX RecyclerView for chat and history lists.
- AndroidX SwipeRefreshLayout only if manual refresh is implemented.

Avoid by default:

- AppCompat, Material Components, ConstraintLayout, Room, Retrofit, Moshi, Gson, Glide, Coil, Picasso, Media3, ExoPlayer, Hilt, Dagger, RxJava, Firebase, AppCenter, analytics SDKs, crash SDKs, icon packs, and annotation-heavy libraries.

If a dependency is proposed, the agent must justify:

- APK size impact.
- Runtime memory impact.
- Why platform APIs are insufficient.
- Whether R8 can shrink it safely.

## Project Structure

Create the Android app under an `android/` directory:

```text
android/
  settings.gradle
  build.gradle
  app/
    build.gradle
    proguard-rules.pro
    src/main/
      AndroidManifest.xml
      java/<package>/
        MainActivity.java
        AppExecutors.java
        EphemeralApplication.java
        data/
          api/
            EphemeralApi.java
            ApiModels.java
            OkHttpEphemeralApi.java
            SessionCookieStore.java
          model/
            Item.java
            ItemMetadata.java
            ItemType.java
            Page.java
            HistoryQuery.java
            FilePreview.java
          session/
            SessionRepository.java
            SessionState.java
        ui/
          common/
          login/
          chat/
          history/
          media/
          preview/
          upload/
        util/
          ByteFormatter.java
          DateFormatter.java
          Result.java
          UiState.java
      res/
        drawable/
        layout/
        mipmap-anydpi-v26/
        values/
```

Use plain Java classes with explicit ownership. Keep UI controllers small and move parsing, network calls, upload state, image loading, and session storage into separate classes.

Recommended ownership:

- `MainActivity`: owns top-level navigation and back behavior.
- `EphemeralApplication`: owns singletons that are safe for process lifetime.
- `EphemeralApi`: declares backend capabilities without endpoint paths.
- `OkHttpEphemeralApi`: later maps capabilities to concrete backend routes.
- `SessionRepository`: persists and clears session data.
- `ChatController`: owns chat paging, event refresh, composer, optimistic sends.
- `HistoryController`: owns filters, history paging, and result rendering.
- `UploadController`: owns queue state, concurrency, progress, retry, cancel.
- `MediaViewerController`: owns image/video viewing and previous/next.
- `TextPreviewController`: owns preview loading, language choice, copy/download/delete.
- `ImageLoader`: owns decode, downsampling, memory cache, and row cancellation.
- `FileResolver`: converts Android `Uri` values into streamable upload descriptors.

No UI controller should perform raw network calls directly. Use the API adapter and typed model classes.

## Build Configuration

Use a standard Android Gradle application project.

Release builds must enable:

```gradle
minifyEnabled true
shrinkResources true
proguardFiles getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
```

Also configure:

```properties
android.nonTransitiveRClass=true
android.nonFinalResIds=false
android.useAndroidX=true
android.enableJetifier=false
```

Disable dependency metadata in release outputs:

```gradle
dependenciesInfo {
    includeInApk = false
    includeInBundle = false
}
```

Keep rules must be minimal and specific. Do not add broad rules such as keeping entire packages unless there is a verified reflection requirement.

## Size Budget

Initial release target:

- APK: aim for less than 12 MB.
- AAB: aim for less than 10 MB.
- No native `.so` files unless explicitly approved.
- No bundled large fonts, videos, sample files, or raster illustration packs.

Every implementation pass should verify size using:

- release APK file size,
- APK Analyzer or `apkanalyzer`,
- dependency tree inspection.

## Resource Rules

- Use vector drawables for icons.
- Use WebP for raster images when raster assets are unavoidable.
- Keep launcher assets minimal.
- Avoid `material-icons-extended` style asset packs.
- Avoid custom font bundles unless a specific font is required.
- Prefer simple XML shape drawables over bitmap backgrounds.
- Keep layouts shallow.
- Use stable item dimensions for thumbnails, progress rows, buttons, and toolbar areas.
- Do not use decorative gradients, large hero artwork, or marketing-style screens.

## UI Design

The app should feel like a compact native utility:

- Dense but readable layouts.
- Fast list scrolling.
- Clear touch targets.
- Native Android back behavior.
- Minimal animations.
- No card-within-card layouts.
- No large decorative panels.
- No visible instructional text for obvious controls.

Use icons for common actions:

- attach,
- send,
- more,
- delete,
- download,
- close,
- copy,
- play,
- previous,
- next,
- search,
- clear.

Use text labels where ambiguity would hurt usability, especially destructive actions and upload states.

## Navigation

Use a simple Activity-based or single-Activity view-controller structure.

Required screens:

- Login/setup.
- Chat.
- History.
- Full-screen media viewer.
- Full-screen text/code preview.

Back behavior:

- Back closes open menus first.
- Back closes upload queue expansion only if it is taking focus.
- Back closes media viewer.
- Back closes text preview.
- Back returns from History to Chat when History was opened from Chat.
- Back exits the app from Chat only when no modal surface is open.

Server selection:

- A mobile app needs a backend base URL because it is not served by the backend like the web UI.
- Provide a small local-development configuration path for the backend base URL.
- If a user-facing server URL screen is built, keep it minimal: URL field, connect button, inline error.
- Do not mix server URL management with authentication logic.

Recommended initial flow:

```text
Launch
  -> load stored server config
  -> validate stored session if present
  -> ask server state
  -> setup screen, login screen, or chat screen
```

Authenticated navigation:

```text
Chat
  -> History
  -> Media Viewer
  -> Text Preview
  -> Logout

History
  -> Chat
  -> Media Viewer
  -> Text Preview
  -> Logout
```

## Data Model

Define strict Java model classes for:

- authenticated session state,
- item,
- item metadata,
- paged item result,
- history query,
- upload task,
- upload progress,
- file preview.

Item types:

- `text`,
- `image`,
- `video`,
- `file`.

Metadata fields required by the UI:

- width,
- height,
- duration,
- MIME type,
- thumbnail reference.

Do not pass raw JSON objects through the UI. Parse responses into typed model classes at the API boundary.

Required model shapes:

```text
Item
  long id
  ItemType type
  String contentRef
  String filename
  long filesizeBytes
  ItemMetadata metadata
  Instant createdAt or long createdAtEpochMillis

ItemMetadata
  int width
  int height
  String duration
  String mime
  String thumbRef

Page<T>
  List<T> items
  long nextCursor
  boolean hasMore

HistoryQuery
  long cursor
  ItemTypeFilter typeFilter
  String query
  boolean searchBody
  LocalDate dateFrom
  LocalDate dateTo
  RecentFilter recent

UploadTask
  long localId
  Uri sourceUri
  String displayName
  long sizeBytes
  UploadStatus status
  long uploadedBytes
  long totalBytes
  int progressPercent
  String errorMessage

FilePreview
  long id
  String filename
  String mime
  String language
  String content
  long filesizeBytes
  Instant createdAt or long createdAtEpochMillis
  String downloadRef
```

Use `long` for IDs, cursor values, file sizes, and byte counters.

Cursor semantics:

- `0` means no cursor was supplied or no next page exists.
- The next page cursor is the ID of the last item in the current page.
- Items are ordered newest-first.
- When merging pages, ignore duplicate IDs.
- When receiving deletion events, remove matching IDs from all in-memory lists.

Upload status values:

- queued,
- uploading,
- done,
- failed,
- canceled.

Send status values for optimistic text:

- sending,
- sent,
- failed.

Recent filter mapping:

```text
any time -> no recent value
last day -> 1d
last 7 days -> 7d
last 14 days -> 14d
last 30 days -> 30d
last 90 days -> 90d
last 6 months -> 6mo
last year -> 1y
```

Supported preview language IDs:

```text
auto
plaintext
go
python
javascript
typescript
jsx
tsx
json
markdown
yaml
toml
html
css
scss
xml
sql
shellscript
make
dockerfile
rust
c
cpp
java
kotlin
ruby
php
lua
```

## API Layer

Create an interface such as `EphemeralApi` with capability-based methods, not URL-based names.

Examples of method categories:

- authenticate,
- logout,
- load chat page,
- send text message,
- upload file,
- delete item,
- load history page,
- load text preview,
- download file,
- observe item events.

Do not include endpoint paths in this guidelines file or in early UI-only scaffolding. Endpoint mapping belongs in the future mobile API contract.

The app must not depend on server-rendered HTML. Mobile responses should be JSON or binary streams once the backend supports them.

Required API interface shape:

```java
public interface EphemeralApi {
    void getServerState(ApiCallback<ServerState> callback);
    void createFirstAccount(String username, String password, ApiCallback<AuthResult> callback);
    void login(String username, String password, ApiCallback<AuthResult> callback);
    void logout(ApiCallback<Void> callback);
    void validateSession(ApiCallback<AuthResult> callback);
    void getRuntimeConfig(ApiCallback<RuntimeConfig> callback);
    void loadChatPage(long cursor, ApiCallback<Page<Item>> callback);
    void sendTextMessage(String text, ApiCallback<Item> callback);
    Cancellable uploadFile(UploadRequest request, UploadProgressListener progress, ApiCallback<Item> callback);
    void deleteItem(long itemId, ApiCallback<Void> callback);
    void loadHistoryPage(HistoryQuery query, ApiCallback<Page<Item>> callback);
    void loadTextPreview(long itemId, ApiCallback<FilePreview> callback);
    Cancellable downloadFile(FileDownloadRequest request, DownloadProgressListener progress, ApiCallback<FileDownloadResult> callback);
    EventSubscription observeItemEvents(ItemEventListener listener);
}
```

The exact callback names may differ, but the implementation must provide equivalent capability boundaries.

API behavior requirements:

- All callbacks must return on the main thread unless clearly documented otherwise.
- A request must expose cancelation when it can outlive the current screen.
- Upload progress must be based on bytes written to the network request body.
- Download progress must be based on bytes read from the network response body.
- HTTP/session errors must map into typed errors, not raw strings.
- A not-found response during delete should be treated as successful local removal.
- Preview-too-large and unsupported-preview errors must be distinguishable for UI fallback.

Typed error categories:

- network unavailable,
- timeout,
- unauthenticated,
- forbidden,
- not found,
- validation error,
- payload too large,
- unsupported preview,
- server error,
- canceled,
- unknown.

## Networking

Use one shared OkHttp client:

- connection pooling enabled,
- cookie jar for session persistence,
- bounded timeouts,
- TLS by default,
- cleartext allowed only for explicit local development configuration,
- no password or session logging.

Implement uploads with a custom streaming request body that reports progress without loading whole files into memory.

Implement downloads with streaming I/O. Do not load full files into memory.

Implement real-time events with a lightweight streaming client. Reconnect with exponential backoff and jitter. Stop the stream when the user logs out.

Threading:

- network callbacks run on a background thread internally;
- UI-facing callbacks are posted to the main thread;
- parsing JSON happens off the main thread for large responses;
- image decode and syntax highlighting use bounded executors;
- executor queues must not grow without bound.

Development backend:

- local development may need cleartext HTTP;
- release builds should default to HTTPS-only unless the user explicitly configures a trusted self-hosted cleartext server;
- keep network security config minimal and scoped.

## Session Storage

Persist only the minimum session material required to stay logged in.

Rules:

- Never store the password.
- Clear session storage on logout.
- Treat authentication failure as session expiry and return to login.
- Use private app storage at minimum.
- Prefer Android Keystore-backed encryption if the implementation can do it without pulling in a large dependency.

Cookie/session rules:

- if the backend uses cookies, persist only the session cookie and expiry metadata;
- never expose cookies to logs or crash output;
- session restoration must happen before starting item events;
- logout clears cookies, local session state, pending auth requests, and event subscriptions;
- upload tasks may remain in failed/canceled state after logout but must not continue using an invalid session.

## Login And Setup

The login screen must support both states:

- first-run setup: title text indicates account creation and submit button creates the account;
- normal login: title text indicates sign-in and submit button logs in.

Fields:

- username,
- password,
- password reveal/hide toggle.

Validation:

- username required,
- password required,
- server error shown inline,
- form disabled while submitting,
- retry allowed after failure.

Login screen layout:

- centered app logo or simple app mark,
- title `Ephemeral`,
- subtitle `Create your first account` in setup mode,
- subtitle `Sign in to continue` in login mode,
- username field,
- password field with reveal/hide icon button,
- submit button labeled `Create account` in setup mode,
- submit button labeled `Login` in login mode,
- inline error region below the submit button.

Behavior:

- Keep the keyboard open after validation errors.
- Disable the submit button while a request is in flight.
- Do not clear the username after failed login.
- Clear the password after failed login/setup.
- On success, immediately enter Chat and start the item event stream.

## Chat Screen

The chat screen contains:

- app title,
- Chat/History navigation,
- logout action,
- paged chat list,
- composer,
- upload queue.

Chat list:

- newest items appear first.
- older items load when the user reaches the list boundary.
- list updates when new items arrive from the event stream.
- item updates refresh visible metadata and thumbnails.
- item deletions remove items immediately.
- stable item IDs are mandatory for smooth updates.

Composer:

- multiline input,
- send button,
- attach button,
- Enter key handling for hardware keyboards: Enter sends, Shift+Enter inserts newline.
- trim before sending.
- optimistic local bubble with retry on failure.

Uploads:

- support multiple selected files.
- support files shared from other Android apps.
- enforce configurable concurrency from app configuration once available.
- show queued/uploading/done/failed/canceled.
- show per-file and total progress.
- support cancel and retry.
- do not keep large file bytes in memory.

Chat screen layout:

- top app bar with title `Ephemeral`;
- compact navigation row or tabs for Chat and History;
- logout action;
- full-height `RecyclerView` for the chat stream;
- bottom composer anchored above system navigation/IME;
- upload queue surface above the composer when uploads exist.

Chat empty state:

- show a compact empty state only when the first page returns no items;
- do not use a marketing-style hero;
- provide the composer as the primary action.

Chat loading states:

- first page loading: small centered progress indicator;
- older page loading: inline row at the pagination boundary;
- event refresh: avoid full-screen loading, update visible items quietly.

Optimistic send behavior:

- create a local-only row immediately after submit;
- show the locally formatted timestamp;
- mark it as sending;
- on success, replace it with the server item by stable ID;
- on failure, keep the row, mark failed, and expose Retry;
- Retry removes the failed local row only after the retry starts.

Event behavior:

- On `item:new`, refresh or insert newest items unless the item is the local optimistic text that just completed.
- On `item:updated`, refresh visible matching rows or reload the current visible page.
- On `item:deleted`, remove the item from chat and history lists and close any viewer showing it.

Upload queue behavior:

- new selected files enter queued state;
- queued uploads start while active uploads are below configured concurrency;
- uploading progress is capped visually below 100 until the server confirms success;
- done sets progress to 100;
- failed keeps the row with error and Retry;
- canceled keeps the row with Retry until cleared;
- Clear completed removes done, failed, and canceled rows;
- closing the queue while active uploads exist collapses it instead of clearing it;
- closing the queue with no active uploads clears it.

## Item Rendering

Text item:

- message body,
- timestamp,
- overflow menu with delete.

Image item:

- thumbnail or full image preview depending on available metadata,
- filename,
- file size,
- timestamp,
- tap opens media viewer.

Video item:

- thumbnail if available,
- placeholder with play icon if thumbnail is unavailable,
- filename,
- file size,
- timestamp,
- tap opens media viewer.

Generic file item:

- filename,
- file size,
- view action,
- download action,
- timestamp,
- overflow menu with delete.

Deletion:

- always confirm permanent deletion.
- close any open viewer for the deleted item.
- remove the deleted item from chat and history views.
- treat already-deleted responses as successful removal.

Common item display rules:

- use local timezone for display;
- compact timestamp format in chat: month, day, hour, minute;
- detailed timestamp format in viewers: month, day, year, hour, minute;
- file sizes use binary units: B, KB, MB, GB, TB with one decimal for non-byte units;
- filenames must be single-line with ellipsize in dense rows and fully visible in viewers;
- overflow menus must be dismissed on outside tap and back press.

RecyclerView requirements:

- use separate view holders for text, image, video, and file;
- stable IDs should be enabled;
- payload updates should refresh progress/metadata without rebinding heavy image views when possible;
- recycled media rows must cancel pending image decode work.

## Media Viewer

Use native full-screen Android UI, not WebView.

Requirements:

- image viewing with fit-center and zoom/pan if implemented without heavy dependencies;
- video viewing with platform playback controls;
- previous and next navigation within the current list;
- filename;
- file size;
- dimensions when available;
- creation time;
- download;
- delete;
- close.

Use platform `VideoView` or `MediaPlayer` first. Do not add Media3/ExoPlayer unless platform playback is inadequate for required formats.

Media viewer behavior:

- opening from Chat navigates within the Chat media order;
- opening from History navigates within the History result order;
- previous/next buttons are disabled at list boundaries;
- left/right hardware keys should navigate when a viewer is open;
- closing a video viewer pauses playback;
- deleting the current media item closes the viewer and removes the item from visible lists;
- failed image decode shows a compact error and still allows download/delete/close;
- failed video playback shows a compact error and still allows download/delete/close.

## History Screen

The history screen contains:

- search field,
- search action,
- clear action,
- type filters,
- date filters,
- recent filter,
- body-search toggle,
- paged gallery/list.

Type filters:

- all,
- images,
- videos,
- files.

Recent filter values:

- any time,
- last day,
- last 7 days,
- last 14 days,
- last 30 days,
- last 90 days,
- last 6 months,
- last year.

Date filters:

- from date,
- to date.

Behavior:

- preserve active filters while paging.
- clear search resets query/date/recent/body filters but preserves type filter.
- images and videos render as gallery thumbnails.
- files render with filename, type, size, view, download, and delete actions.
- more results load when the user reaches the list boundary.

History screen layout:

- top app bar with title `Ephemeral`;
- navigation row or tabs for Chat and History;
- search row with query field, Search action, and Clear action when filters are active;
- date/recent row;
- body-search checkbox or switch;
- type filter segmented control or compact chips;
- grid/list `RecyclerView`.

History loading states:

- first page loading: small centered progress indicator;
- empty result: compact `No results` state;
- pagination loading: inline row at the bottom;
- filter change: reset cursor, clear previous results only after the new request starts.

Filter behavior:

- query value is trimmed before request;
- changing type filter preserves query/date/recent/body filters;
- clear action removes query, dateFrom, dateTo, recent, and searchBody;
- clear action preserves type filter;
- selecting a recent filter should not clear explicit dates in the UI, but backend behavior may use the most restrictive lower bound;
- dateTo is inclusive by day.

## Text And Code Preview

Use a native full-screen preview surface.

Required controls:

- title,
- metadata line,
- syntax/language selector,
- render status,
- copy,
- download,
- delete,
- close.

Supported language choices:

- auto detect,
- plain text,
- Go,
- Python,
- JavaScript,
- TypeScript,
- JSX,
- TSX,
- JSON,
- Markdown,
- YAML,
- TOML,
- HTML,
- CSS,
- SCSS,
- XML,
- SQL,
- Shell,
- Makefile,
- Dockerfile,
- Rust,
- C,
- C++,
- Java,
- Kotlin,
- Ruby,
- PHP,
- Lua.

Use a bounded, native text renderer:

- monospace text,
- horizontal scrolling or soft-wrap toggle,
- line numbers if feasible without hurting performance,
- plain-text fallback on render failure.

For syntax highlighting, prefer a small custom span-based highlighter for common token classes. Do not embed Monaco, Shiki, a browser engine, or a large TextMate grammar bundle.

Preview behavior:

- open immediately with loading state after the user taps View;
- if the backend returns unsupported preview, close preview and offer open/download;
- if the backend returns preview too large, close preview and offer download;
- copy writes the full preview content to Android clipboard and briefly changes label to `Copied`;
- language selection rerenders the currently loaded content;
- delete from preview confirms, deletes, closes preview, and removes item from visible lists.

Preview rendering constraints:

- enforce a maximum text length in memory based on backend preview response;
- use `TextView`/`Spannable` or a custom lightweight view;
- never run syntax highlighting on the main thread for large content;
- cancel stale highlight work if the user changes language quickly;
- show line numbers only if they do not harm scrolling performance.

## Image Loading

Do not use Glide, Coil, or Picasso by default.

Implement a small image loader:

- background decode executor,
- `LruCache` for memory cache,
- downsample using target view dimensions,
- cancel work when a RecyclerView row is recycled,
- avoid decoding original large images for thumbnails,
- use placeholders while loading,
- handle decode failures cleanly.

## File Access

Use Android Storage Access Framework for user-selected files.

Rules:

- hold URI permissions only when necessary;
- stream from `ContentResolver`;
- preserve display name and size when available;
- support unknown size gracefully;
- use foreground notification if long uploads continue while the app is backgrounded.

Downloads:

- use `DownloadManager` or `ACTION_CREATE_DOCUMENT`;
- never write arbitrary paths directly;
- report download failure clearly.

Share intents:

- support `ACTION_SEND` for one file or text payload when feasible;
- support `ACTION_SEND_MULTIPLE` for multiple file URIs;
- convert shared text into the composer draft, not an automatic send;
- convert shared file URIs into queued uploads after the user is authenticated;
- if the user is not authenticated, store the incoming intent only in memory and process it after login.

## Performance Requirements

- Chat and history must use RecyclerView.
- Use stable IDs where possible.
- Use DiffUtil or equivalent minimal updates.
- Never decode images or parse large files on the main thread.
- Never upload or download through byte arrays for large files.
- Cap simultaneous uploads.
- Debounce repeated event-triggered refreshes.
- Reuse OkHttp client and executor pools.
- Avoid reflection-heavy frameworks.

Memory targets:

- avoid keeping full-resolution image bitmaps in memory;
- use `inSampleSize` or equivalent decode options for thumbnails;
- release video playback resources when leaving the viewer;
- clear preview content from memory when the preview closes;
- keep upload queue metadata only, not file byte arrays.

Latency targets:

- first screen should render immediately after session state is known;
- list row binding must not perform blocking I/O;
- event reconnect should not block the UI;
- filter changes should show visible feedback within one frame.

## Error Handling

Handle:

- no network,
- timeout,
- authentication expiry,
- failed login,
- failed message send,
- failed upload,
- canceled upload,
- failed preview load,
- unsupported preview,
- preview too large,
- failed delete,
- failed download,
- media decode/playback failure.

Use concise inline errors for form and upload rows. Use dialogs only for confirmation and destructive actions.

## Security Requirements

- TLS by default.
- Development cleartext must be explicitly configured and isolated.
- Never log credentials, session cookies, file contents, or preview contents.
- Clear session and in-memory sensitive state on logout.
- Use constant-time assumptions only on backend; Android client must not attempt to validate passwords locally.
- Validate file URIs defensively.
- Treat server-provided filenames as display data, not trusted filesystem paths.

Input handling:

- trim text messages before sending;
- preserve internal newlines in text messages;
- validate upload size before starting when size is known;
- handle unknown upload size without trusting it to be small;
- escape or render all server text as text, never as markup;
- do not execute preview content.

## Empty Directory Implementation Sequence

An implementation agent starting from an empty directory should build in this order:

1. Create the Android Gradle project under `android/`.
2. Configure Java, AndroidX, release minification, resource shrinking, non-transitive R, and dependency metadata exclusion.
3. Add resource skeleton: app name, colors, dimensions, styles, vector icons, launcher icon.
4. Add model classes and typed result/error wrappers.
5. Add `EphemeralApi` capability interface with no endpoint paths.
6. Add a temporary debug-only fake API implementation for UI development, excluded from release wiring.
7. Add `OkHttpEphemeralApi` skeleton with endpoint mapping intentionally unimplemented until the backend contract exists.
8. Add session repository and cookie store.
9. Add main activity, navigation shell, and back handling.
10. Add login/setup screen.
11. Add chat screen with fake paged data.
12. Add RecyclerView item view holders for text, image, video, and file.
13. Add composer and optimistic send state.
14. Add upload queue state machine and UI using fake upload progress.
15. Add file picker and share-intent handling.
16. Add history screen with filters and fake paged data.
17. Add media viewer.
18. Add text/code preview screen.
19. Add lightweight image loader.
20. Replace fake API calls with real API calls when the backend mobile contract is available.
21. Run debug UI verification.
22. Run release build, R8, resource shrink, APK size inspection, and manual acceptance tests.

Release builds must not use the fake API implementation. If the real API contract is not ready, release builds should fail clearly at compile time or startup configuration.

## Minimum Layout Inventory

Create XML layouts for at least:

- `activity_main.xml`: root container for screen swapping.
- `screen_login.xml`: setup/login form.
- `screen_chat.xml`: app bar, navigation, chat list, upload queue, composer.
- `screen_history.xml`: app bar, filters, history list/grid.
- `row_chat_text.xml`.
- `row_chat_image.xml`.
- `row_chat_video.xml`.
- `row_chat_file.xml`.
- `row_upload.xml`.
- `row_history_image.xml`.
- `row_history_video.xml`.
- `row_history_file.xml`.
- `dialog_confirm_delete.xml` or native alert configuration.
- `screen_media_viewer.xml`.
- `screen_text_preview.xml`.
- `row_pagination_loading.xml`.
- `view_empty_state.xml`.

Keep layouts shallow. Prefer `LinearLayout`, `FrameLayout`, `GridLayout`, and `RecyclerView`. Use `ConstraintLayout` only if approved despite the size preference.

## Minimum Drawable Inventory

Use vector drawables for:

- attach,
- send,
- more vertical,
- delete,
- download,
- close,
- copy,
- play,
- previous,
- next,
- search,
- clear,
- eye,
- eye off,
- file,
- image placeholder,
- video placeholder,
- warning/error.

Use simple XML shape drawables for:

- buttons,
- text fields,
- chat bubbles,
- progress bars,
- selected filter background,
- viewer toolbar background.

## Completion Criteria

The Android project is complete for this phase when:

- it can be generated from this guideline without reading the web frontend source;
- it builds a debug APK;
- it builds a release APK with minification and resource shrinking;
- release wiring cannot accidentally use fake API data;
- APK size is measured and documented in the build output or final report;
- every feature in Product Scope has a screen, state model, and UI path;
- all API usage goes through capability methods with no route strings outside the API adapter;
- no WebView, Compose, Flutter, React Native, or large image/media dependency is present;
- large uploads/downloads/images are streamed or decoded incrementally;
- logout clears session state and stops event streams;
- manual acceptance tests in this document can be executed.

## Testing Requirements

Add tests for:

- typed JSON parsing,
- history filter state construction,
- pagination merge behavior,
- upload state transitions,
- retry/cancel behavior,
- session expiry handling,
- text preview language selection,
- image loader cache key behavior.

Manual verification before completion:

- release APK builds successfully;
- R8/shrinkResources are enabled for release;
- APK size is reported;
- login/setup works;
- chat send works;
- upload queue works with multiple files;
- cancel/retry upload works;
- image viewer works;
- video viewer works;
- generic file preview/download works;
- history filters and paging work;
- deletion updates chat and history;
- logout clears session.

## Implementation Discipline

- Keep code reviewable and explicit.
- Prefer small classes over global managers.
- Avoid placeholder production code.
- Do not leave fake API implementations wired into release builds.
- Keep endpoint mapping isolated in one API adapter once the backend contract is ready.
- Keep UI strings in resources.
- Keep colors, dimensions, and styles in resources.
- Run release builds regularly, not only debug builds.
- Measure APK size after each new dependency.
