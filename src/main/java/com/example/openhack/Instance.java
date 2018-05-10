package com.example.openhack;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Instance implements Serializable {
    String name;
    Map<String, String> endpoints;
    String players;
//    String status;
}
