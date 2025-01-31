package smithereen.templates;

import com.mitchellbosecke.pebble.error.PebbleException;
import com.mitchellbosecke.pebble.extension.Filter;
import com.mitchellbosecke.pebble.extension.escaper.SafeString;
import com.mitchellbosecke.pebble.template.EvaluationContext;
import com.mitchellbosecke.pebble.template.PebbleTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import smithereen.activitypub.objects.Actor;
import smithereen.data.Group;
import smithereen.data.SizedImage;
import smithereen.data.User;

public class PictureForAvatarFilter implements Filter{
	@Override
	public Object apply(Object input, Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) throws PebbleException{
		SizedImage image;
		String additionalClasses="";
		if(input instanceof Actor){
			Actor actor=(Actor) input;
			image=actor.getAvatar();
			if(actor instanceof User && ((User)actor).gender==User.Gender.FEMALE)
				additionalClasses=" female";
			else if(actor instanceof Group)
				additionalClasses=" group";
		}else{
			return "";
		}

		String typeStr=(String) args.get("type");
		SizedImage.Type type=SizedImage.Type.fromSuffix(
					switch(typeStr){
						case "s" -> "sqs";
						case "m" -> "sqm";
						case "l" -> "sql";
						case "xl" -> "sqxl";
						default -> typeStr;
					}
				);
		int size=type.getMaxWidth();
		boolean isRect=type.isRect();
		if(isRect)
			typeStr=typeStr.substring(1);
		if(args.containsKey("size"))
			size=Templates.asInt(args.get("size"));
		if(image==null)
			return new SafeString("<span class=\"ava avaPlaceholder size"+typeStr.toUpperCase()+additionalClasses+"\" style=\"width: "+size+"px;height: "+size+"px\"></span>");

		int width, height;
		if(isRect){
			SizedImage.Dimensions sz=image.getDimensionsForSize(type);
			width=sz.width;
			height=sz.height;
		}else{
			width=height=size;
		}

		List<String> classes=new ArrayList<>();
		classes.add("avaImage");
		if(args.containsKey("classes")){
			classes.add(args.get("classes").toString());
		}
		return new SafeString("<span class=\"ava avaHasImage size"+typeStr.toUpperCase()+"\">"+image.generateHTML(type, classes, null, width, height)+"</span>");
	}

	@Override
	public List<String> getArgumentNames(){
		return Arrays.asList("type", "size", "classes");
	}
}
