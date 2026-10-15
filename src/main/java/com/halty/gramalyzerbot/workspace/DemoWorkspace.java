package com.halty.gramalyzerbot.workspace;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.*;
import static com.halty.gramalyzerbot.workspace.Models.*;

@Component
@ConditionalOnProperty(name="gramalyzer.demo",havingValue="true")
public class DemoWorkspace implements ApplicationRunner {
    private final WorkspaceStore store;
    public DemoWorkspace(WorkspaceStore store) {this.store=store;}
    @Override public void run(ApplicationArguments args) {
        if (!store.channels().isEmpty()) return;
        store.saveChannel(new Channel("design","Design Dispatch","@designdispatch","Dd","purple",24832,12.8,186420,6.4,"A daily dose of design, creativity, and things worth saving.",false,"Europe/Berlin",true,false,"",null));
        store.saveChannel(new Channel("tech","The Tech Edit","@thetechedit","Te","blue",18460,8.2,124580,5.8,"Technology, thoughtfully curated.",false,"Europe/Berlin",true,false,"",null));
        store.saveChannel(new Channel("makers","Makers Club","@makersclub","Mc","orange",9204,16.4,74320,8.2,"A place for people who make things.",false,"Europe/Berlin",true,false,"",null));
        String[] titles={"Good design starts with a better question.","Five websites we kept coming back to","Inside the creative process: a little less noise","Your next favorite font is probably here","A small collection of very good ideas","What makes a brand feel human?"};
        long[] views={12480,10320,9240,8650,7980,6240}; long[] reactions={842,615,528,486,425,374}; long[] shares={156,124,98,87,74,62};
        for(int i=0;i<titles.length;i++) store.insert(new Post("demo-"+i,i==4?"tech":i==5?"makers":"design",titles[i],titles[i]+"\n\nA considered selection of inspiration, useful resources, and fresh perspectives for our community.","published",Instant.now().minusSeconds((i+1)*86400L),views[i],reactions[i],shares[i],i%2==0?"Inspiration":"Resources","sent",null,null));
        String[] upcoming={"Monday moodboard: a fresh perspective","The tools behind our favorite workflows","Community spotlight: made with intention","A few things worth sharing this week"};
        for(int i=0;i<upcoming.length;i++) store.insert(new Post("queue-"+i,i==1?"tech":i==2?"makers":"design",upcoming[i],upcoming[i],i==3?"draft":"scheduled",Instant.now().plusSeconds((i+1)*86400L),0,0,0,"Community","pending",null,null));
    }
}
