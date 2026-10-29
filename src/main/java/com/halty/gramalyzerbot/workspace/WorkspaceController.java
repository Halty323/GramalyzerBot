package com.halty.gramalyzerbot.workspace;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;
import java.util.Optional;
import static com.halty.gramalyzerbot.workspace.Models.*;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
    private final WorkspaceService workspace; private final ChannelPhotos photos; private final WorkspaceMedia files;
    public WorkspaceController(WorkspaceService workspace,ChannelPhotos photos,WorkspaceMedia files) {this.workspace=workspace;this.photos=photos;this.files=files;}
    @GetMapping("/health") public Map<String,Object> health() {return Map.of("ok",true,"demo",workspace.workspace().demo(),"backend","spring");}
    @GetMapping("/workspace") public Workspace workspace() {return workspace.workspace();}
    @PatchMapping("/channels/{id}") public Channel settings(@PathVariable String id, @RequestBody ObjectNode body) {return workspace.settings(id,body);}
    @GetMapping("/channels/{id}/photo") public ResponseEntity<byte[]> photo(@PathVariable String id, HttpServletResponse res) {
        Long chatId=workspace.channel(id).telegramChatId();
        return (chatId==null?Optional.<ChannelPhotos.Photo>empty():photos.photo(chatId)).map(p->{
            res.setHeader("Cache-Control","private, max-age=3600"); // replaces the API-wide no-store, so avatars don't refetch on every render
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).eTag(p.tag()).body(p.bytes());
        }).orElseGet(()->ResponseEntity.notFound().build());
    }
    /** Uploads one picture, video or GIF for a post: the raw file as the body, its type as Content-Type, ?name= its file name. */
    @PostMapping("/media") public ResponseEntity<MediaRef> upload(HttpServletRequest req, @RequestParam(required=false) String name) throws java.io.IOException {
        String kind=WorkspaceMedia.kindOf(req.getContentType());
        if(kind==null) throw new WorkspaceService.Problem(415,"Upload a JPEG, PNG, WebP or GIF picture, or an MP4 video.");
        long limit=WorkspaceMedia.limitFor(kind);
        if(req.getContentLengthLong()>limit) throw new WorkspaceService.Problem(413,"photo".equals(kind)?"Pictures can be up to 10 MB.":"Videos and GIFs can be up to 50 MB.");
        byte[] data=req.getInputStream().readNBytes((int)limit+1);
        return ResponseEntity.status(201).body(files.save(req.getContentType(),name,data));
    }
    @GetMapping("/media/{id}") public ResponseEntity<byte[]> media(@PathVariable String id, HttpServletResponse res) {
        return files.file(id).map(f->{
            res.setHeader("Cache-Control","private, max-age=86400"); // an upload never changes
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).body(f.data());
        }).orElseGet(()->ResponseEntity.notFound().build());
    }
    @PostMapping("/posts") public ResponseEntity<Post> create(@Valid @RequestBody PostInput input) {return ResponseEntity.status(201).body(workspace.save(null,input));}
    @PatchMapping("/posts/{id}") public Post update(@PathVariable String id, @Valid @RequestBody PostInput input) {return workspace.save(id,input);}
    @DeleteMapping("/posts/{id}") public ResponseEntity<Void> delete(@PathVariable String id) {workspace.delete(id); return ResponseEntity.noContent().build();}
    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(WorkspaceService.Problem.class) ResponseEntity<?> problem(WorkspaceService.Problem e) {return ResponseEntity.status(e.status).body(Map.of("error",e.getMessage()));}
        @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> invalid(MethodArgumentNotValidException e) {var error=e.getBindingResult().getFieldErrors().getFirst(); return ResponseEntity.badRequest().body(Map.of("error",error.getField()+": "+error.getDefaultMessage()));}
        @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<?> unreadable() {return ResponseEntity.badRequest().body(Map.of("error","The request contains an invalid value or date."));}
    }
}
