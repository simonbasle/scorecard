package io.spring.team.scorecard.graphql;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.apollographql.apollo.ApolloCall;
import com.apollographql.apollo.ApolloClient;
import com.apollographql.apollo.api.Error;
import com.apollographql.apollo.api.Input;
import com.apollographql.apollo.api.Response;
import com.apollographql.apollo.exception.ApolloException;
import io.spring.team.scorecard.AssignableUsersQuery;
import io.spring.team.scorecard.IssueCountQuery;
import io.spring.team.scorecard.IssueDataQuery;
import io.spring.team.scorecard.data.Issue;
import io.spring.team.scorecard.type.IssueState;
import io.spring.team.scorecard.type.PullRequestState;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static reactor.core.publisher.Sinks.EmitFailureHandler.FAIL_FAST;

public class GraphQLClient {

	private final Log logger = LogFactory.getLog(GraphQLClient.class);

	private final ApolloClient apolloClient;

	public GraphQLClient(String githubToken) {
		OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
		clientBuilder.addInterceptor(new AuthorizationInterceptor(githubToken));
		this.apolloClient = ApolloClient.builder()
				.serverUrl("https://api.github.com/graphql")
				.okHttpClient(clientBuilder.build())
				.build();
	}


	public Mono<Integer> searchNumberOfIssuesAndPRs(String searchQuery) {
		logger.debug("query: " + searchQuery);
		return Mono.create(sink -> {
			this.apolloClient.query(new IssueCountQuery(searchQuery))
					.enqueue(new ApolloCall.Callback<IssueCountQuery.Data>() {
						@Override
						public void onResponse(@NotNull Response<IssueCountQuery.Data> response) {
							sink.success(response.getData().search().issueCount());
						}

						@Override
						public void onFailure(@NotNull ApolloException e) {
							sink.error(e);
						}
					});
		});
	}

	public Flux<Issue> searchIssuesAndPRs(String searchQuery) {
		logger.debug("query: " + searchQuery);
		Sinks.Many<Issue> sink = Sinks.many().unicast().onBackpressureBuffer();
		AtomicInteger processed = new AtomicInteger(0);

		ApolloCall.Callback<IssueDataQuery.Data> issueDataPaginatedCallback = new ApolloCall.Callback<IssueDataQuery.Data>() {
			@Override
			public void onResponse(@NotNull Response<IssueDataQuery.Data> response) {
				if (response.hasErrors()) {
					String messages = response.getErrors().stream().map(Error::getMessage).collect(Collectors.joining(", "));
					sink.emitError(new IllegalStateException("Error in response: " + messages), FAIL_FAST);
					return;
				}

				final IssueDataQuery.Search search = response.getData().search();

				List<IssueDataQuery.Node> issues = search.nodes();
				int issueCount = search.issueCount();
				int start = processed.getAndAdd(issues.size());
				int end = processed.get();
				logger.info("Fetching issues " + start + ".." + end + "/" + issueCount);

				emitIssues(issues, sink);

				boolean hasMorePages = search.pageInfo().hasNextPage();
				if (hasMorePages) {
					logger.debug("Fetching additional page");
					String nextCursor = search.pageInfo().endCursor();
					apolloClient.query(new IssueDataQuery(searchQuery, Input.fromNullable(nextCursor)))
					.enqueue(this);
				}
				else {
					logger.debug("Last page");
					sink.emitComplete(FAIL_FAST);
				}
			}

			@Override
			public void onFailure(@NotNull ApolloException e) {
				sink.emitError(e, FAIL_FAST);
			}
		};
		this.apolloClient.query(new IssueDataQuery(searchQuery, Input.absent()))
					.enqueue(issueDataPaginatedCallback);

		return sink.asFlux();
	}

	private void emitIssues(List<IssueDataQuery.Node> issues, Sinks.Many<Issue> sink) {
		issues.stream()
				.peek(node -> {
					if (!(node instanceof IssueDataQuery.AsIssue) && !(node instanceof IssueDataQuery.AsPullRequest)) {
						logger.debug("Excluding element that is not an issue: " + node.getClass().getName() + ": " + node);
					}
				})
				.filter(node -> node instanceof IssueDataQuery.AsIssue || node instanceof IssueDataQuery.AsPullRequest)
				.map(issue -> issueFromNode(issue))
				.forEach(i -> sink.emitNext(i, FAIL_FAST));
	}

	@NotNull
	private Issue issueFromNode(IssueDataQuery.Node node) {
		if (node instanceof IssueDataQuery.AsIssue) {
			return issueFromNodeIssue((IssueDataQuery.AsIssue) node);
		}
		if (node instanceof IssueDataQuery.AsPullRequest) {
			return issueFromNodePr((IssueDataQuery.AsPullRequest) node);
		}
		throw new IllegalArgumentException("Unprocessable node: " + node);
	}

	private Issue issueFromNodeIssue(IssueDataQuery.AsIssue issue) {
		int id = issue.number();
		String author = issue.author().login();
		LocalDateTime createdAt = LocalDateTime.parse(String.valueOf(issue.createdAt()), DateTimeFormatter.ISO_DATE_TIME);
		boolean isOpen = issue.state() == IssueState.OPEN;
		LocalDateTime closedAt = null;
		if (issue.closedAt() != null) {
			closedAt = LocalDateTime.parse(String.valueOf(issue.closedAt()), DateTimeFormatter.ISO_DATE_TIME);
		}

		List<String> labels = issue.labels().nodes().stream().map(IssueDataQuery.Node1::name).collect(Collectors.toList());
		List<String> participants = issue.participants().nodes().stream().map(IssueDataQuery.Node2::login).collect(Collectors.toList());

		String milestone = issue.milestone() == null ? null : issue.milestone().title();

		return new Issue(id, author, createdAt, isOpen, closedAt, labels, participants, milestone);
	}

	private Issue issueFromNodePr(IssueDataQuery.AsPullRequest pr) {
		int id = pr.number();
		String author = pr.author().login();
		LocalDateTime createdAt = LocalDateTime.parse(String.valueOf(pr.createdAt()), DateTimeFormatter.ISO_DATE_TIME);
		boolean isOpen = pr.state() == PullRequestState.OPEN;
		LocalDateTime closedAt = null;
		if (pr.closedAt() != null) {
			closedAt = LocalDateTime.parse(String.valueOf(pr.closedAt()), DateTimeFormatter.ISO_DATE_TIME);
		}

		List<String> labels = pr.labels().nodes().stream().map(IssueDataQuery.Node3::name).collect(Collectors.toList());
		List<String> participants = pr.participants().nodes().stream().map(IssueDataQuery.Node4::login).collect(Collectors.toList());

		String milestone = pr.milestone() == null ? null : pr.milestone().title();

		return new Issue(id, author, createdAt, isOpen, closedAt, labels, participants, milestone);
	}

	public Flux<String> findAssignableUsers(String org, String repo) {
		return Flux.create(sink -> {
			this.apolloClient.query(new AssignableUsersQuery(org, repo))
					.enqueue(new ApolloCall.Callback<AssignableUsersQuery.Data>() {
						@Override
						public void onResponse(@NotNull Response<AssignableUsersQuery.Data> response) {
							response.getData().repository().assignableUsers()
									.nodes().forEach(node -> sink.next(node.login()));
							sink.complete();
						}

						@Override
						public void onFailure(@NotNull ApolloException e) {
							sink.error(e);
						}
					});
		});
	}

	private static class AuthorizationInterceptor implements Interceptor {

		private final String token;

		public AuthorizationInterceptor(String token) {
			this.token = token;
		}

		@Override
		public okhttp3.Response intercept(Chain chain) throws IOException {
			Request request = chain.request().newBuilder()
					.addHeader("Authorization", "Bearer " + this.token).build();
			return chain.proceed(request);
		}
	}
}
