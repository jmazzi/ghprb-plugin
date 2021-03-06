package org.jenkinsci.plugins.ghprb;

import java.io.IOException;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHPullRequest;

/**
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class GhprbPullRequest{
	private final int id;
	private final String author;
	private Date updated;
	private String head;
	private boolean mergeable;
	private String reponame;
	private String target;

	private boolean shouldRun = false;
	private boolean accepted = false;
	@Deprecated private transient boolean askedForApproval; // TODO: remove

	private transient Ghprb ml;
	private transient GhprbRepository repo;

	GhprbPullRequest(GHPullRequest pr, Ghprb helper, GhprbRepository repo) {
		id = pr.getNumber();
		updated = pr.getUpdatedAt();
		head = pr.getHead().getSha();
		author = pr.getUser().getLogin();
		reponame = repo.getName();
		target = pr.getBase().getRef();

		this.ml = helper;
		this.repo = repo;

		if(helper.isWhitelisted(author)){
			accepted = true;
			shouldRun = true;
		}else{
			Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.INFO, "Author of #{0} {1} on {2} not in whitelist!", new Object[]{id, author, reponame});
			repo.addComment(id, GhprbTrigger.getDscp().getRequestForTestingPhrase());
		}

		Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.INFO, "Created pull request #{0} on {1} by {2} updated at: {3} SHA: {4}", new Object[]{id, reponame, author, updated, head});
	}

	public void init(Ghprb helper, GhprbRepository repo) {
		this.ml = helper;
		this.repo = repo;
	}

	public void check(GHPullRequest pr){
		if(target == null) target = pr.getBase().getRef(); // If this instance was created before target was introduced (before v1.8), it can be null.

		if(isUpdated(pr)){
			Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.INFO, "Pull request builder: pr #{0} was updated on {1} at {2}", new Object[]{id, reponame, updated});

			int commentsChecked = checkComments(pr);
			boolean newCommit   = checkCommit(pr.getHead().getSha());

			if(!newCommit && commentsChecked == 0){
				Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.INFO, "Pull request was updated on repo {0} but there aren''t any new comments nor commits - that may mean that commit status was updated.", reponame);
			}
			updated = pr.getUpdatedAt();
		}

		if(shouldRun){
			checkMergeable(pr);
			build();
		}
	}

	public void check(GHIssueComment comment) {
		try {
			checkComment(comment);
			updated = comment.getUpdatedAt();
		} catch (IOException ex) {
			Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.SEVERE, "Couldn't check comment #" + comment.getId(), ex);
			return;
		}
		if (shouldRun) {
			build();
		}
	}

	private boolean isUpdated(GHPullRequest pr){
		boolean ret = false;
		ret = ret || updated.compareTo(pr.getUpdatedAt()) < 0;
		ret = ret || !pr.getHead().getSha().equals(head);

		return ret;
	}

	private void build(){
		shouldRun = false;
		String message = ml.getBuilds().build(this);

		repo.createCommitStatus(head, GHCommitState.PENDING, null, message,id);

		Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.INFO, message);
	}

	// returns false if no new commit
	private boolean checkCommit(String sha){
		if(head.equals(sha)) return false;

		if(Logger.getLogger(GhprbPullRequest.class.getName()).isLoggable(Level.FINE)){
			Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.FINE, "New commit. Sha: {0} => {1}", new Object[]{head, sha});
		}

		head = sha;
		if(accepted){
			shouldRun = true;
		}
		return true;
	}

	private void checkComment(GHIssueComment comment) throws IOException {
		String sender = comment.getUser().getLogin();
		String body = comment.getBody();

		// add to whitelist
		if (ml.isWhitelistPhrase(body) && ml.isAdmin(sender)){
			if(!ml.isWhitelisted(author)) {
				ml.addWhitelist(author);
			}
			accepted = true;
			shouldRun = true;
		}

		// ok to test
		if(ml.isOktotestPhrase(body) && ml.isAdmin(sender)){
			accepted = true;
			shouldRun = true;
		}

		// test this please
		if (ml.isRetestPhrase(body)){
			if(ml.isAdmin(sender)){
				shouldRun = true;
			}else if(accepted && ml.isWhitelisted(sender) ){
				shouldRun = true;
			}
		}
	}

	private int checkComments(GHPullRequest pr) {
		int count = 0;
		try {
			for (GHIssueComment comment : pr.getComments()) {
				if (updated.compareTo(comment.getUpdatedAt()) < 0) {
					count++;
					try {
						checkComment(comment);
					} catch (IOException ex) {
						Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.SEVERE, "Couldn't check comment #" + comment.getId(), ex);
					}
				}
			}
		} catch (IOException e) {
			Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.SEVERE, "Couldn't obtain comments.", e);
		}
		return count;
	}

	private void checkMergeable(GHPullRequest pr) {
		try {
			mergeable = pr.getMergeable();
		} catch (IOException e) {
			mergeable = false;
			Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.SEVERE, "Couldn't obtain mergeable status.", e);
		}
	}

	@Override
	public boolean equals(Object obj) {
		if(!(obj instanceof GhprbPullRequest)) return false;

		GhprbPullRequest o = (GhprbPullRequest) obj;
		return o.id == id;
	}

	@Override
	public int hashCode() {
		int hash = 7;
		hash = 89 * hash + this.id;
		return hash;
	}

	public int getId() {
		return id;
	}

	public String getHead() {
		return head;
	}

	public boolean isMergeable() {
		return mergeable;
	}

	public String getTarget(){
		return target;
	}
}
