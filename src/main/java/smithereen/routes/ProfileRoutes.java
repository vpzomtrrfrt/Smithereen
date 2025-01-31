package smithereen.routes;

import org.jetbrains.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.safety.Whitelist;

import java.net.URI;
import java.net.URLEncoder;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static smithereen.Utils.*;

import smithereen.Config;
import smithereen.Utils;
import smithereen.activitypub.ActivityPubWorker;
import smithereen.activitypub.objects.PropertyValue;
import smithereen.data.Account;
import smithereen.data.ForeignUser;
import smithereen.data.FriendRequest;
import smithereen.data.FriendshipStatus;
import smithereen.data.Group;
import smithereen.data.ListAndTotal;
import smithereen.data.Post;
import smithereen.data.SessionInfo;
import smithereen.data.SizedImage;
import smithereen.data.User;
import smithereen.data.UserInteractions;
import smithereen.data.WebDeltaResponse;
import smithereen.data.feed.NewsfeedEntry;
import smithereen.data.notifications.Notification;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.lang.Lang;
import smithereen.storage.GroupStorage;
import smithereen.storage.NewsfeedStorage;
import smithereen.storage.NotificationsStorage;
import smithereen.storage.PostStorage;
import smithereen.storage.UserStorage;
import smithereen.templates.RenderedTemplateResponse;
import spark.Request;
import spark.Response;
import spark.utils.StringUtils;

public class ProfileRoutes{
	public static Object profile(Request req, Response resp) throws SQLException{
		SessionInfo info=Utils.sessionInfo(req);
		@Nullable Account self=info!=null ? info.account : null;
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		Lang l=lang(req);
		if(user!=null){
			boolean isSelf=self!=null && self.user.id==user.id;
			int[] postCount={0};
			int offset=Utils.parseIntOrDefault(req.queryParams("offset"), 0);
			List<Post> wall=PostStorage.getWallPosts(user.id, false, 0, 0, offset, postCount, false);
			RenderedTemplateResponse model=new RenderedTemplateResponse("profile", req).with("title", user.getFullName()).with("user", user).with("wall", wall).with("own", self!=null && self.user.id==user.id).with("postCount", postCount[0]);
			model.with("pageOffset", offset);

			Set<Integer> postIDs=wall.stream().map((Post p)->p.id).collect(Collectors.toSet());

			if(req.attribute("mobile")==null){
				Map<Integer, ListAndTotal<Post>> allComments=PostStorage.getRepliesForFeed(postIDs);
				for(Post post:wall){
					ListAndTotal<Post> comments=allComments.get(post.id);
					if(comments!=null){
						post.repliesObjects=comments.list;
						post.totalTopLevelComments=comments.total;
						post.getAllReplyIDs(postIDs);
					}
				}
			}

			HashMap<Integer, UserInteractions> interactions=PostStorage.getPostInteractions(postIDs, self!=null ? self.user.id : 0);
			model.with("postInteractions", interactions);

			int[] friendCount={0};
			List<User> friends=UserStorage.getRandomFriendsForProfile(user.id, friendCount);
			model.with("friendCount", friendCount[0]).with("friends", friends);

			if(self!=null && user.id!=self.user.id){
				int mutualFriendCount=UserStorage.getMutualFriendsCount(user.id, self.user.id);
				if(mutualFriendCount>0){
					List<User> mutualFriends=UserStorage.getRandomMutualFriendsForProfile(user.id, self.user.id);
					model.with("mutualFriendCount", mutualFriendCount).with("mutualFriends", mutualFriends);
				}
			}

			ArrayList<PropertyValue> profileFields=new ArrayList<>();
			if(user.birthDate!=null)
				profileFields.add(new PropertyValue(l.get("birth_date"), l.formatDay(user.birthDate.toLocalDate())));
			if(StringUtils.isNotEmpty(user.summary))
				profileFields.add(new PropertyValue(l.get("profile_about"), user.summary));
			if(user.attachment!=null)
				user.attachment.stream().filter(o->o instanceof PropertyValue).forEach(o->profileFields.add((PropertyValue) o));
			model.with("profileFields", profileFields);

			if(info!=null && self!=null){
				model.with("draftAttachments", info.postDraftAttachments);
			}
			if(self!=null){
				if(user.id==self.user.id){
					// lang keys for profile picture update UI
					jsLangKey(req, "update_profile_picture", "save", "profile_pic_select_square_version", "drag_or_choose_file", "choose_file",
							"drop_files_here", "picture_too_wide", "picture_too_narrow", "ok", "error", "error_loading_picture",
							"remove_profile_picture", "confirm_remove_profile_picture", "choose_file_mobile");
				}else{
					FriendshipStatus status=UserStorage.getFriendshipStatus(self.user.id, user.id);
					if(status==FriendshipStatus.FRIENDS){
						model.with("isFriend", true);
						model.with("friendshipStatusText", Utils.lang(req).get("X_is_your_friend", Map.of("name", user.firstName)));
					}else if(status==FriendshipStatus.REQUEST_SENT){
						model.with("friendRequestSent", true);
						model.with("friendshipStatusText", Utils.lang(req).get("you_sent_friend_req_to_X", Map.of("name", user.getFirstAndGender())));
					}else if(status==FriendshipStatus.REQUEST_RECVD){
						model.with("friendRequestRecvd", true);
						model.with("friendshipStatusText", Utils.lang(req).get("X_sent_you_friend_req", Map.of("gender", user.gender, "name", user.firstName)));
					}else if(status==FriendshipStatus.FOLLOWING){
						model.with("following", true);
						model.with("friendshipStatusText", Utils.lang(req).get("you_are_following_X", Map.of("name", user.getFirstAndGender())));
					}else if(status==FriendshipStatus.FOLLOWED_BY){
						model.with("followedBy", true);
						model.with("friendshipStatusText", Utils.lang(req).get("X_is_following_you", Map.of("gender", user.gender, "name", user.firstName)));
					}else if(status==FriendshipStatus.FOLLOW_REQUESTED){
						model.with("followRequested", true);
						model.with("friendshipStatusText", Utils.lang(req).get("waiting_for_X_to_accept_follow_req", Map.of("gender", user.gender, "name", user.firstName)));
					}
					model.with("isBlocked", UserStorage.isUserBlocked(self.user.id, user.id));
					model.with("isSelfBlocked", UserStorage.isUserBlocked(user.id, self.user.id));
					jsLangKey(req, "block", "unblock", "unfollow", "remove_friend");
				}
			}else{
				HashMap<String, String> meta=new LinkedHashMap<>();
				meta.put("og:type", "profile");
				meta.put("og:site_name", Config.serverDisplayName);
				meta.put("og:title", user.getFullName());
				meta.put("og:url", user.url.toString());
				meta.put("og:username", user.getFullUsername());
				if(StringUtils.isNotEmpty(user.firstName))
					meta.put("og:first_name", user.firstName);
				if(StringUtils.isNotEmpty(user.lastName))
					meta.put("og:last_name", user.lastName);
				String descr=l.get("X_friends", Map.of("count", friendCount[0]))+", "+l.get("X_posts", Map.of("count", postCount[0]));
				if(StringUtils.isNotEmpty(user.summary))
					descr+="\n"+Jsoup.clean(user.summary, Whitelist.none());
				meta.put("og:description", descr);
				if(user.gender==User.Gender.MALE)
					meta.put("og:gender", "male");
				else if(user.gender==User.Gender.FEMALE)
					meta.put("og:gender", "female");
				if(user.hasAvatar()){
					URI img=user.getAvatar().getUriForSizeAndFormat(SizedImage.Type.LARGE, SizedImage.Format.JPEG);
					if(img!=null){
						SizedImage.Dimensions size=user.getAvatar().getDimensionsForSize(SizedImage.Type.LARGE);
						meta.put("og:image", img.toString());
						meta.put("og:image:width", size.width+"");
						meta.put("og:image:height", size.height+"");
					}
				}
				model.with("metaTags", meta);
				model.with("moreMetaTags", Map.of("description", descr));
			}

			if(user instanceof ForeignUser)
				model.with("noindex", true);
			model.with("activityPubURL", user.activityPubID);

			model.addNavBarItem(user.getFullName(), null, isSelf ? l.get("this_is_you") : null);

			model.with("groups", GroupStorage.getUserGroups(user.id));
			jsLangKey(req, "yes", "no", "delete_post", "delete_post_confirm", "delete_reply", "delete_reply_confirm", "remove_friend", "cancel", "delete", "post_form_cw", "post_form_cw_placeholder", "attach_menu_photo", "attach_menu_cw", "attach_menu_poll", "max_file_size_exceeded", "max_attachment_count_exceeded", "remove_attachment");
			jsLangKey(req, "create_poll_question", "create_poll_options", "create_poll_add_option", "create_poll_delete_option", "create_poll_multi_choice", "create_poll_anonymous", "create_poll_time_limit", "X_days", "X_hours");
			return model;
		}else{
			Group g=GroupStorage.getByUsername(username);
			if(g!=null){
				return GroupsRoutes.groupProfile(req, resp, g);
			}
			throw new ObjectNotFoundException("err_user_not_found");
		}
	}

	public static Object confirmSendFriendRequest(Request req, Response resp, Account self) throws SQLException{
		req.attribute("noHistory", true);
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		if(user!=null){
			if(user.id==self.user.id){
				return wrapError(req, resp, "err_cant_friend_self");
			}
			ensureUserNotBlocked(self.user, user);
			FriendshipStatus status=UserStorage.getFriendshipStatus(self.user.id, user.id);
			Lang l=lang(req);
			if(status==FriendshipStatus.FOLLOWED_BY){
				if(isAjax(req) && verifyCSRF(req, resp)){
					UserStorage.followUser(self.user.id, user.id, !(user instanceof ForeignUser));
					if(user instanceof ForeignUser){
						ActivityPubWorker.getInstance().sendFollowActivity(self.user, (ForeignUser)user);
					}
					return new WebDeltaResponse(resp).refresh();
				}else{
					RenderedTemplateResponse model=new RenderedTemplateResponse("form_page", req);
					model.with("targetUser", user);
					model.with("contentTemplate", "send_friend_request").with("formAction", user.getProfileURL("doSendFriendRequest")).with("submitButton", l.get("add_friend"));
					return model;
				}
			}else if(status==FriendshipStatus.NONE){
				if(user.supportsFriendRequests()){
					RenderedTemplateResponse model=new RenderedTemplateResponse("send_friend_request", req);
					model.with("targetUser", user);
					return wrapForm(req, resp, "send_friend_request", user.getProfileURL("doSendFriendRequest"), l.get("send_friend_req_title"), "add_friend", model);
				}else{
					return doSendFriendRequest(req, resp, self);
				}
			}else if(status==FriendshipStatus.FRIENDS){
				return wrapError(req, resp, "err_already_friends");
			}else if(status==FriendshipStatus.REQUEST_RECVD){
				return wrapError(req, resp, "err_have_incoming_friend_req");
			}else{ // REQ_SENT
				return wrapError(req, resp, "err_friend_req_already_sent");
			}
		}else{
			throw new ObjectNotFoundException("err_user_not_found");
		}
	}

	public static Object doSendFriendRequest(Request req, Response resp, Account self) throws SQLException{
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		if(user!=null){
			if(user.id==self.user.id){
				return Utils.wrapError(req, resp, "err_cant_friend_self");
			}
			ensureUserNotBlocked(self.user, user);
			FriendshipStatus status=UserStorage.getFriendshipStatus(self.user.id, user.id);
			if(status==FriendshipStatus.NONE || status==FriendshipStatus.FOLLOWED_BY){
				if(status==FriendshipStatus.NONE && user.supportsFriendRequests()){
					UserStorage.putFriendRequest(self.user.id, user.id, req.queryParams("message"), !(user instanceof ForeignUser));
					if(user instanceof ForeignUser){
						ActivityPubWorker.getInstance().sendFriendRequestActivity(self.user, (ForeignUser)user, req.queryParams("message"));
					}
				}else{
					UserStorage.followUser(self.user.id, user.id, !(user instanceof ForeignUser));
					if(user instanceof ForeignUser){
						ActivityPubWorker.getInstance().sendFollowActivity(self.user, (ForeignUser)user);
					}else{
						ActivityPubWorker.getInstance().sendAddToFriendsCollectionActivity(self.user, user);
					}
				}
				if(isAjax(req)){
					return new WebDeltaResponse(resp).refresh();
				}
				resp.redirect(Utils.back(req));
				return "";
			}else if(status==FriendshipStatus.FRIENDS){
				return Utils.wrapError(req, resp, "err_already_friends");
			}else if(status==FriendshipStatus.REQUEST_RECVD){
				return Utils.wrapError(req, resp, "err_have_incoming_friend_req");
			}else{ // REQ_SENT
				return Utils.wrapError(req, resp, "err_friend_req_already_sent");
			}
		}else{
			throw new ObjectNotFoundException("err_user_not_found");
		}
	}

	public static Object confirmRemoveFriend(Request req, Response resp, Account self) throws SQLException{
		req.attribute("noHistory", true);
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		if(user!=null){
			FriendshipStatus status=UserStorage.getFriendshipStatus(self.user.id, user.id);
			if(status==FriendshipStatus.FRIENDS || status==FriendshipStatus.REQUEST_SENT || status==FriendshipStatus.FOLLOWING || status==FriendshipStatus.FOLLOW_REQUESTED){
				Lang l=Utils.lang(req);
				String back=Utils.back(req);
				return new RenderedTemplateResponse("generic_confirm", req).with("message", l.get("confirm_unfriend_X", Map.of("name", user.getFirstLastAndGender()))).with("formAction", user.getProfileURL("doRemoveFriend")+"?_redir="+URLEncoder.encode(back)).with("back", back);
			}else{
				return Utils.wrapError(req, resp, "err_not_friends");
			}
		}else{
			throw new ObjectNotFoundException("err_user_not_found");
		}
	}

	private static Object friends(Request req, Response resp, User user, Account self) throws SQLException{
		RenderedTemplateResponse model=new RenderedTemplateResponse("friends", req);
		model.with("friendList", UserStorage.getFriendListForUser(user.id)).with("owner", user).with("tab", 0);
		model.with("title", lang(req).get("friends"));
		if(self!=null && user.id!=self.user.id){
			int mutualCount=UserStorage.getMutualFriendsCount(self.user.id, user.id);
			model.with("mutualCount", mutualCount);
		}
		model.with("tab", "friends");
		jsLangKey(req, "remove_friend", "yes", "no");
		return model;
	}

	public static Object friends(Request req, Response resp) throws SQLException{
		User user=getUserOrThrow(req);
		SessionInfo info=Utils.sessionInfo(req);
		@Nullable Account self=info!=null ? info.account : null;
		return friends(req, resp, user, self);
	}

	public static Object ownFriends(Request req, Response resp, Account self) throws SQLException{
		return friends(req, resp, self.user, self);
	}

	public static Object mutualFriends(Request req, Response resp, Account self) throws SQLException{
		User user=getUserOrThrow(req);
		if(user.id==self.user.id)
			throw new ObjectNotFoundException("err_user_not_found");
		RenderedTemplateResponse model=new RenderedTemplateResponse("friends", req);
		model.with("friendList", UserStorage.getMutualFriendListForUser(user.id, self.user.id)).with("owner", user).with("tab", 0);
		model.with("title", lang(req).get("friends"));
		model.with("tab", "mutual");
		int mutualCount=UserStorage.getMutualFriendsCount(self.user.id, user.id);
		model.with("mutualCount", mutualCount);
		jsLangKey(req, "remove_friend", "yes", "no");
		return model;
	}

	public static Object followers(Request req, Response resp) throws SQLException{
		SessionInfo info=Utils.sessionInfo(req);
		@Nullable Account self=info!=null ? info.account : null;
		String username=req.params(":username");
		User user;
		if(username==null){
			if(requireAccount(req, resp)){
				user=sessionInfo(req).account.user;
			}else{
				return "";
			}
		}else{
			user=UserStorage.getByUsername(username);
		}
		if(user!=null){
			RenderedTemplateResponse model=new RenderedTemplateResponse("friends", req);
			model.with("title", lang(req).get("followers")).with("toolbarTitle", lang(req).get("friends"));
			model.with("friendList", UserStorage.getNonMutualFollowers(user.id, true, true)).with("owner", user).with("followers", true).with("tab", "followers");
			if(self!=null && user.id!=self.user.id){
				int mutualCount=UserStorage.getMutualFriendsCount(self.user.id, user.id);
				model.with("mutualCount", mutualCount);
			}
			return model;
		}
		throw new ObjectNotFoundException("err_user_not_found");
	}

	public static Object following(Request req, Response resp) throws SQLException{
		SessionInfo info=Utils.sessionInfo(req);
		@Nullable Account self=info!=null ? info.account : null;
		String username=req.params(":username");
		User user;
		if(username==null){
			if(requireAccount(req, resp)){
				user=sessionInfo(req).account.user;
			}else{
				return "";
			}
		}else{
			user=UserStorage.getByUsername(username);
		}
		if(user!=null){
			RenderedTemplateResponse model=new RenderedTemplateResponse("friends", req);
			model.with("title", lang(req).get("following")).with("toolbarTitle", lang(req).get("friends"));
			model.with("friendList", UserStorage.getNonMutualFollowers(user.id, false, true)).with("owner", user).with("following", true).with("tab", "following");
			if(self!=null && user.id!=self.user.id){
				int mutualCount=UserStorage.getMutualFriendsCount(self.user.id, user.id);
				model.with("mutualCount", mutualCount);
			}
			jsLangKey(req, "unfollow", "yes", "no");
			return model;
		}
		throw new ObjectNotFoundException("err_user_not_found");
	}

	public static Object incomingFriendRequests(Request req, Response resp, Account self) throws SQLException{
		List<FriendRequest> requests=UserStorage.getIncomingFriendRequestsForUser(self.user.id, 0, 100);
		RenderedTemplateResponse model=new RenderedTemplateResponse("friend_requests", req);
		model.with("friendRequests", requests);
		model.with("title", lang(req).get("friend_requests")).with("toolbarTitle", lang(req).get("friends")).with("owner", self.user);
		return model;
	}

	public static Object respondToFriendRequest(Request req, Response resp, Account self) throws SQLException{
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		if(user!=null){
			if(req.queryParams("accept")!=null){
				if(user instanceof ForeignUser){
					UserStorage.acceptFriendRequest(self.user.id, user.id, false);
					ActivityPubWorker.getInstance().sendFollowActivity(self.user, (ForeignUser) user);
				}else{
					UserStorage.acceptFriendRequest(self.user.id, user.id, true);
					Notification n=new Notification();
					n.type=Notification.Type.FRIEND_REQ_ACCEPT;
					n.actorID=self.user.id;
					NotificationsStorage.putNotification(user.id, n);
					ActivityPubWorker.getInstance().sendAddToFriendsCollectionActivity(self.user, user);
					NewsfeedStorage.putEntry(user.id, self.user.id, NewsfeedEntry.Type.ADD_FRIEND, null);
				}
				NewsfeedStorage.putEntry(self.user.id, user.id, NewsfeedEntry.Type.ADD_FRIEND, null);
			}else if(req.queryParams("decline")!=null){
				UserStorage.deleteFriendRequest(self.user.id, user.id);
				if(user instanceof ForeignUser){
					ActivityPubWorker.getInstance().sendRejectFriendRequestActivity(self.user, (ForeignUser) user);
				}
			}
			if(isAjax(req))
				return new WebDeltaResponse(resp).refresh();
			resp.redirect(Utils.back(req));
		}else{
			throw new ObjectNotFoundException("err_user_not_found");
		}
		return "";
	}

	public static Object doRemoveFriend(Request req, Response resp, Account self) throws SQLException{
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		if(user!=null){
			FriendshipStatus status=UserStorage.getFriendshipStatus(self.user.id, user.id);
			if(status==FriendshipStatus.FRIENDS || status==FriendshipStatus.REQUEST_SENT || status==FriendshipStatus.FOLLOWING || status==FriendshipStatus.FOLLOW_REQUESTED){
				UserStorage.unfriendUser(self.user.id, user.id);
				if(user instanceof ForeignUser){
					ActivityPubWorker.getInstance().sendUnfriendActivity(self.user, user);
				}
				if(status==FriendshipStatus.FRIENDS){
					ActivityPubWorker.getInstance().sendRemoveFromFriendsCollectionActivity(self.user, user);
					NewsfeedStorage.deleteEntry(self.user.id, user.id, NewsfeedEntry.Type.ADD_FRIEND);
					if(!(user instanceof ForeignUser)){
						NewsfeedStorage.deleteEntry(user.id, self.user.id, NewsfeedEntry.Type.ADD_FRIEND);
					}
				}
				if(isAjax(req)){
					resp.type("application/json");
					if("list".equals(req.queryParams("from")))
						return new WebDeltaResponse().remove("frow"+user.id);
					else
						return new WebDeltaResponse().refresh();
				}
				resp.redirect(Utils.back(req));
			}else{
				return Utils.wrapError(req, resp, "err_not_friends");
			}
		}else{
			throw new ObjectNotFoundException("err_user_not_found");
		}
		return "";
	}

	public static Object confirmBlockUser(Request req, Response resp, Account self) throws SQLException{
		User user=getUserOrThrow(req);
		Lang l=Utils.lang(req);
		String back=Utils.back(req);
		return new RenderedTemplateResponse("generic_confirm", req).with("message", l.get("confirm_block_user_X", Map.of("name", user.getFirstLastAndGender()))).with("formAction", "/users/"+user.id+"/block?_redir="+URLEncoder.encode(back)).with("back", back);
	}

	public static Object confirmUnblockUser(Request req, Response resp, Account self) throws SQLException{
		User user=getUserOrThrow(req);
		Lang l=Utils.lang(req);
		String back=Utils.back(req);
		return new RenderedTemplateResponse("generic_confirm", req).with("message", l.get("confirm_unblock_user_X", Map.of("name", user.getFirstLastAndGender()))).with("formAction", "/users/"+user.id+"/unblock?_redir="+URLEncoder.encode(back)).with("back", back);
	}

	public static Object blockUser(Request req, Response resp, Account self) throws SQLException{
		User user=getUserOrThrow(req);
		FriendshipStatus status=UserStorage.getFriendshipStatus(self.user.id, user.id);
		UserStorage.blockUser(self.user.id, user.id);
		if(user instanceof ForeignUser)
			ActivityPubWorker.getInstance().sendBlockActivity(self.user, (ForeignUser) user);
		if(status==FriendshipStatus.FRIENDS){
			ActivityPubWorker.getInstance().sendRemoveFromFriendsCollectionActivity(self.user, user);
			NewsfeedStorage.deleteEntry(self.user.id, user.id, NewsfeedEntry.Type.ADD_FRIEND);
			if(!(user instanceof ForeignUser)){
				NewsfeedStorage.deleteEntry(user.id, self.user.id, NewsfeedEntry.Type.ADD_FRIEND);
			}
		}
		if(isAjax(req))
			return new WebDeltaResponse(resp).refresh();
		resp.redirect(back(req));
		return "";
	}

	public static Object unblockUser(Request req, Response resp, Account self) throws SQLException{
		User user=getUserOrThrow(req);
		UserStorage.unblockUser(self.user.id, user.id);
		if(user instanceof ForeignUser)
			ActivityPubWorker.getInstance().sendUndoBlockActivity(self.user, (ForeignUser) user);
		if(isAjax(req))
			return new WebDeltaResponse(resp).refresh();
		resp.redirect(back(req));
		return "";
	}


	private static User getUserOrThrow(Request req) throws SQLException{
		int id=parseIntOrDefault(req.params(":id"), 0);
		if(id==0)
			throw new ObjectNotFoundException("err_user_not_found");
		User user=UserStorage.getById(id);
		if(user==null)
			throw new ObjectNotFoundException("err_user_not_found");
		return user;
	}
}
