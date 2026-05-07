# 在线简历制作系统 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个功能完整的在线简历制作系统，支持多模板、实时预览、PDF导出、用户管理和简历数据持久化

**Architecture:** 采用前后端分离架构，前端使用 React + TypeScript 构建简历编辑界面和实时预览组件，后端使用 Spring Boot 提供 RESTful API 和数据持久化。采用组件化设计，简历模板通过 JSON Schema 定义，支持灵活扩展。

**Tech Stack:** 
- 前端: React 18, TypeScript, Vite, TailwindCSS, jsPDF, React Hook Form
- 后端: Spring Boot 3.5, Spring Data JPA, MySQL/HSQLDB, Lombok
- 构建工具: Maven (后端), npm (前端)

---

## 一、项目结构规划

### 1.1 后端模块结构
```
backend/
├── src/main/java/top/javarem/omniAgent/
│   ├── ResumeApplication.java                 # Spring Boot 主启动类
│   ├── config/                                # 配置模块
│   │   ├── CorsConfig.java                    # 跨域配置
│   │   └── SecurityConfig.java                # 安全配置（可选）
│   ├── controller/                            # REST 控制器
│   │   ├── ResumeController.java              # 简历 CRUD 接口
│   │   └── TemplateController.java            # 模板管理接口
│   ├── service/                               # 业务逻辑层
│   │   ├── ResumeService.java                 # 简历业务逻辑
│   │   └── TemplateService.java               # 模板业务逻辑
│   ├── repository/                             # 数据访问层
│   │   ├── ResumeRepository.java              # 简历数据仓库
│   │   └── TemplateRepository.java            # 模板数据仓库
│   ├── entity/                                 # 实体类
│   │   ├── Resume.java                        # 简历实体
│   │   └── Template.java                      # 模板实体
│   └── dto/                                    # 数据传输对象
│       ├── ResumeDTO.java                     # 简历数据传输对象
│       └── TemplateDTO.java                   # 模板数据传输对象
└── src/main/resources/
    ├── application.yml                         # 应用配置
    └── data.sql                                # 初始化数据
```

### 1.2 前端模块结构
```
frontend/
├── src/
│   ├── components/                             # 通用组件
│   │   ├── ui/                                 # UI基础组件
│   │   │   ├── Button.tsx
│   │   │   ├── Input.tsx
│   │   │   └── Card.tsx
│   │   ├── ResumeEditor/                       # 简历编辑器
│   │   │   ├── PersonalInfoForm.tsx           # 个人信息表单
│   │   │   ├── EducationForm.tsx              # 教育经历表单
│   │   │   ├── ExperienceForm.tsx             # 工作经历表单
│   │   │   ├── SkillsForm.tsx                 # 技能表单
│   │   │   └── ProjectsForm.tsx               # 项目经历表单
│   │   ├── TemplateGallery/                   # 模板选择器
│   │   │   ├── TemplateCard.tsx               # 模板卡片
│   │   │   └── TemplateGrid.tsx               # 模板网格
│   │   └── ResumePreview/                      # 简历预览
│   │       ├── ClassicTemplate.tsx            # 经典模板
│   │       ├── ModernTemplate.tsx             # 现代模板
│   │       └── MinimalTemplate.tsx            # 简约模板
│   ├── pages/                                  # 页面
│   │   ├── HomePage.tsx                       # 首页/仪表盘
│   │   ├── EditorPage.tsx                     # 编辑器页面
│   │   └── PreviewPage.tsx                    # 预览页面
│   ├── hooks/                                  # 自定义 Hooks
│   │   ├── useResume.ts                       # 简历状态管理
│   │   └── useTemplate.ts                     # 模板管理
│   ├── services/                               # API 服务
│   │   └── api.ts                             # API 请求封装
│   ├── types/                                  # TypeScript 类型定义
│   │   └── resume.ts                          # 简历相关类型
│   ├── App.tsx                                 # 应用入口
│   └── main.tsx                                # 渲染入口
├── public/
│   └── index.html
├── package.json
├── tsconfig.json
└── vite.config.ts
```

---

## 二、核心功能模块

### 2.1 简历数据模型 (Resume Schema)
```typescript
interface Resume {
  id: string;
  title: string;
  personalInfo: {
    name: string;
    email: string;
    phone: string;
    location: string;
    summary: string;
    avatar?: string;
  };
  education: Array<{
    school: string;
    degree: string;
    major: string;
    startDate: string;
    endDate: string;
    gpa?: string;
  }>;
  experience: Array<{
    company: string;
    position: string;
    location: string;
    startDate: string;
    endDate: string;
    description: string;
  }>;
  skills: Array<{
    category: string;
    items: string[];
  }>;
  projects: Array<{
    name: string;
    role: string;
    description: string;
    technologies: string[];
    startDate: string;
    endDate: string;
  }>;
  templateId: string;
  createdAt: string;
  updatedAt: string;
}
```

---

## 三、开发阶段与任务清单

### 阶段一：项目初始化与基础架构 (预计 2 天)

#### 任务 1.1: 后端 Spring Boot 项目初始化
**Files:**
- Create: `backend/src/main/java/top/javarem/omniAgent/ResumeApplication.java`
- Create: `backend/src/main/java/top/javarem/omniAgent/config/CorsConfig.java`
- Create: `backend/src/main/resources/application.yml`
- Modify: `pom.xml` (添加必要依赖)

- [ ] **Step 1: 创建 Spring Boot 主启动类**

```java
package top.javarem.omniAgent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ResumeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResumeApplication.class, args);
    }
}
```

- [ ] **Step 2: 创建跨域配置**

```java
package top.javarem.omniAgent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {
    
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
```

- [ ] **Step 3: 创建 application.yml**

```yaml
spring:
  application:
    name: resume-service
  datasource:
    url: jdbc:hsqldb:mem:resumedb
    driver-class-name: org.hsqldb.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true
    defer-datasource-initialization: true
  sql:
    init:
      mode: always

server:
  port: 8080
```

- [ ] **Step 4: 添加 pom.xml 依赖**

在 `pom.xml` 的 `<dependencies>` 中添加：
```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

- [ ] **Step 5: 验证后端启动**

```bash
cd backend && mvn spring-boot:run
```

预期：服务在 8080 端口启动，控制台显示 "Started ResumeApplication"

---

#### 任务 1.2: 前端项目初始化
**Files:**
- Create: `frontend/src/types/resume.ts`
- Create: `frontend/src/services/api.ts`
- Create: `frontend/src/App.tsx`

- [ ] **Step 1: 创建简历类型定义**

```typescript
// frontend/src/types/resume.ts

export interface PersonalInfo {
  name: string;
  email: string;
  phone: string;
  location: string;
  summary: string;
  avatar?: string;
}

export interface Education {
  id: string;
  school: string;
  degree: string;
  major: string;
  startDate: string;
  endDate: string;
  gpa?: string;
}

export interface Experience {
  id: string;
  company: string;
  position: string;
  location: string;
  startDate: string;
  endDate: string;
  description: string;
}

export interface SkillCategory {
  id: string;
  category: string;
  items: string[];
}

export interface Project {
  id: string;
  name: string;
  role: string;
  description: string;
  technologies: string[];
  startDate: string;
  endDate: string;
}

export interface Resume {
  id: string;
  title: string;
  personalInfo: PersonalInfo;
  education: Education[];
  experience: Experience[];
  skills: SkillCategory[];
  projects: Project[];
  templateId: string;
  createdAt: string;
  updatedAt: string;
}

export const createEmptyResume = (): Resume => ({
  id: '',
  title: '我的简历',
  personalInfo: {
    name: '',
    email: '',
    phone: '',
    location: '',
    summary: '',
  },
  education: [],
  experience: [],
  skills: [],
  projects: [],
  templateId: 'classic',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
});
```

- [ ] **Step 2: 创建 API 服务**

```typescript
// frontend/src/services/api.ts

import type { Resume } from '../types/resume';

const API_BASE = 'http://localhost:8080/api';

export const api = {
  // 简历 API
  async getResumes(): Promise<Resume[]> {
    const res = await fetch(`${API_BASE}/resumes`);
    if (!res.ok) throw new Error('Failed to fetch resumes');
    return res.json();
  },

  async getResume(id: string): Promise<Resume> {
    const res = await fetch(`${API_BASE}/resumes/${id}`);
    if (!res.ok) throw new Error('Failed to fetch resume');
    return res.json();
  },

  async createResume(resume: Omit<Resume, 'id' | 'createdAt' | 'updatedAt'>): Promise<Resume> {
    const res = await fetch(`${API_BASE}/resumes`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(resume),
    });
    if (!res.ok) throw new Error('Failed to create resume');
    return res.json();
  },

  async updateResume(id: string, resume: Partial<Resume>): Promise<Resume> {
    const res = await fetch(`${API_BASE}/resumes/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(resume),
    });
    if (!res.ok) throw new Error('Failed to update resume');
    return res.json();
  },

  async deleteResume(id: string): Promise<void> {
    const res = await fetch(`${API_BASE}/resumes/${id}`, {
      method: 'DELETE',
    });
    if (!res.ok) throw new Error('Failed to delete resume');
  },

  // 模板 API
  async getTemplates(): Promise<Array<{ id: string; name: string; thumbnail: string }>> {
    const res = await fetch(`${API_BASE}/templates`);
    if (!res.ok) throw new Error('Failed to fetch templates');
    return res.json();
  },
};
```

- [ ] **Step 3: 创建基础 App.tsx**

```tsx
// frontend/src/App.tsx

import { useState, useEffect } from 'react';
import { api } from './services/api';
import type { Resume } from './types/resume';
import HomePage from './pages/HomePage';
import EditorPage from './pages/EditorPage';
import PreviewPage from './pages/PreviewPage';

type Page = 'home' | 'editor' | 'preview';

function App() {
  const [currentPage, setCurrentPage] = useState<Page>('home');
  const [resumes, setResumes] = useState<Resume[]>([]);
  const [currentResume, setCurrentResume] = useState<Resume | null>(null);

  useEffect(() => {
    loadResumes();
  }, []);

  const loadResumes = async () => {
    try {
      const data = await api.getResumes();
      setResumes(data);
    } catch (error) {
      console.error('Failed to load resumes:', error);
    }
  };

  const handleCreateResume = () => {
    setCurrentResume(null);
    setCurrentPage('editor');
  };

  const handleEditResume = (resume: Resume) => {
    setCurrentResume(resume);
    setCurrentPage('editor');
  };

  const handlePreviewResume = (resume: Resume) => {
    setCurrentResume(resume);
    setCurrentPage('preview');
  };

  const handleSaveResume = async (resume: Resume) => {
    try {
      if (resume.id) {
        await api.updateResume(resume.id, resume);
      } else {
        await api.createResume(resume);
      }
      await loadResumes();
      setCurrentPage('home');
    } catch (error) {
      console.error('Failed to save resume:', error);
    }
  };

  const handleDeleteResume = async (id: string) => {
    try {
      await api.deleteResume(id);
      await loadResumes();
    } catch (error) {
      console.error('Failed to delete resume:', error);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50">
      {currentPage === 'home' && (
        <HomePage
          resumes={resumes}
          onCreate={handleCreateResume}
          onEdit={handleEditResume}
          onPreview={handlePreviewResume}
          onDelete={handleDeleteResume}
        />
      )}
      {currentPage === 'editor' && (
        <EditorPage
          resume={currentResume}
          onSave={handleSaveResume}
          onCancel={() => setCurrentPage('home')}
        />
      )}
      {currentPage === 'preview' && currentResume && (
        <PreviewPage
          resume={currentResume}
          onBack={() => setCurrentPage('home')}
        />
      )}
    </div>
  );
}

export default App;
```

- [ ] **Step 4: 启动前端开发服务器**

```bash
cd frontend && npm run dev
```

预期：前端在 5173 端口启动，可以访问 http://localhost:5173

---

### 阶段二：后端 API 开发 (预计 3 天)

#### 任务 2.1: 实体类和数据访问层
**Files:**
- Create: `backend/src/main/java/top/javarem/omniAgent/entity/ResumeEntity.java`
- Create: `backend/src/main/java/top/javarem/omniAgent/entity/TemplateEntity.java`
- Create: `backend/src/main/java/top/javarem/omniAgent/repository/ResumeRepository.java`
- Create: `backend/src/main/java/top/javarem/omniAgent/repository/TemplateRepository.java`

- [ ] **Step 1: 创建简历实体类**

```java
package top.javarem.omniAgent.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "resumes")
public class ResumeEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    @Column(nullable = false)
    private String title;
    
    @Column(columnDefinition = "TEXT")
    private String personalInfoJson;
    
    @Column(columnDefinition = "TEXT")
    private String educationJson;
    
    @Column(columnDefinition = "TEXT")
    private String experienceJson;
    
    @Column(columnDefinition = "TEXT")
    private String skillsJson;
    
    @Column(columnDefinition = "TEXT")
    private String projectsJson;
    
    private String templateId;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: 创建模板实体类**

```java
package top.javarem.omniAgent.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "templates")
public class TemplateEntity {
    
    @Id
    private String id;
    
    @Column(nullable = false)
    private String name;
    
    @Column(columnDefinition = "TEXT")
    private String thumbnail;
    
    @Column(columnDefinition = "TEXT")
    private String stylesJson;
    
    private boolean active;
}
```

- [ ] **Step 3: 创建简历数据仓库**

```java
package top.javarem.omniAgent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import top.javarem.omniAgent.entity.ResumeEntity;

@Repository
public interface ResumeRepository extends JpaRepository<ResumeEntity, String> {
}
```

- [ ] **Step 4: 创建模板数据仓库**

```java
package top.javarem.omniAgent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import top.javarem.omniAgent.entity.TemplateEntity;
import java.util.List;

@Repository
public interface TemplateRepository extends JpaRepository<TemplateEntity, String> {
    List<TemplateEntity> findByActiveTrue();
}
```

- [ ] **Step 5: 运行测试验证**

```bash
cd backend && mvn test -Dtest=ResumeRepositoryTest
```

---

#### 任务 2.2: DTO 和服务层
**Files:**
- Create: `backend/src/main/java/top/javarem/omniAgent/dto/ResumeDTO.java`
- Create: `backend/src/main/java/top/javarem/omniAgent/dto/TemplateDTO.java`
- Create: `backend/src/main/java/top/javarem/omniAgent/service/ResumeService.java`
- Create: `backend/src/main/java/top/javarem/omniAgent/service/TemplateService.java`

- [ ] **Step 1: 创建简历 DTO**

```java
package top.javarem.omniAgent.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeDTO {
    private String id;
    private String title;
    private Map<String, Object> personalInfo;
    private List<Map<String, Object>> education;
    private List<Map<String, Object>> experience;
    private List<Map<String, Object>> skills;
    private List<Map<String, Object>> projects;
    private String templateId;
    private String createdAt;
    private String updatedAt;
}
```

- [ ] **Step 2: 创建模板 DTO**

```java
package top.javarem.omniAgent.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TemplateDTO {
    private String id;
    private String name;
    private String thumbnail;
}
```

- [ ] **Step 3: 创建简历服务类**

```java
package top.javarem.omniAgent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.javarem.omniAgent.dto.ResumeDTO;
import top.javarem.omniAgent.entity.ResumeEntity;
import top.javarem.omniAgent.repository.ResumeRepository;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ResumeService {
    
    private final ResumeRepository resumeRepository;
    private final ObjectMapper objectMapper;
    
    public List<ResumeDTO> getAllResumes() {
        return resumeRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
    
    public Optional<ResumeDTO> getResumeById(String id) {
        return resumeRepository.findById(id).map(this::toDTO);
    }
    
    public ResumeDTO createResume(ResumeDTO dto) {
        ResumeEntity entity = toEntity(dto);
        entity = resumeRepository.save(entity);
        return toDTO(entity);
    }
    
    public Optional<ResumeDTO> updateResume(String id, ResumeDTO dto) {
        return resumeRepository.findById(id)
                .map(entity -> {
                    updateEntityFromDTO(entity, dto);
                    entity = resumeRepository.save(entity);
                    return toDTO(entity);
                });
    }
    
    public boolean deleteResume(String id) {
        if (resumeRepository.existsById(id)) {
            resumeRepository.deleteById(id);
            return true;
        }
        return false;
    }
    
    private ResumeDTO toDTO(ResumeEntity entity) {
        ResumeDTO dto = new ResumeDTO();
        dto.setId(entity.getId());
        dto.setTitle(entity.getTitle());
        dto.setTemplateId(entity.getTemplateId());
        dto.setCreatedAt(entity.getCreatedAt().toString());
        dto.setUpdatedAt(entity.getUpdatedAt().toString());
        
        try {
            if (entity.getPersonalInfoJson() != null) {
                dto.setPersonalInfo(objectMapper.readValue(entity.getPersonalInfoJson(), Map.class));
            }
            if (entity.getEducationJson() != null) {
                dto.setEducation(objectMapper.readValue(entity.getEducationJson(), List.class));
            }
            if (entity.getExperienceJson() != null) {
                dto.setExperience(objectMapper.readValue(entity.getExperienceJson(), List.class));
            }
            if (entity.getSkillsJson() != null) {
                dto.setSkills(objectMapper.readValue(entity.getSkillsJson(), List.class));
            }
            if (entity.getProjectsJson() != null) {
                dto.setProjects(objectMapper.readValue(entity.getProjectsJson(), List.class));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse resume JSON", e);
        }
        
        return dto;
    }
    
    private ResumeEntity toEntity(ResumeDTO dto) {
        ResumeEntity entity = new ResumeEntity();
        entity.setTitle(dto.getTitle() != null ? dto.getTitle() : "Untitled Resume");
        entity.setTemplateId(dto.getTemplateId() != null ? dto.getTemplateId() : "classic");
        updateEntityFromDTO(entity, dto);
        return entity;
    }
    
    private void updateEntityFromDTO(ResumeEntity entity, ResumeDTO dto) {
        if (dto.getTitle() != null) entity.setTitle(dto.getTitle());
        if (dto.getTemplateId() != null) entity.setTemplateId(dto.getTemplateId());
        
        try {
            if (dto.getPersonalInfo() != null) {
                entity.setPersonalInfoJson(objectMapper.writeValueAsString(dto.getPersonalInfo()));
            }
            if (dto.getEducation() != null) {
                entity.setEducationJson(objectMapper.writeValueAsString(dto.getEducation()));
            }
            if (dto.getExperience() != null) {
                entity.setExperienceJson(objectMapper.writeValueAsString(dto.getExperience()));
            }
            if (dto.getSkills() != null) {
                entity.setSkillsJson(objectMapper.writeValueAsString(dto.getSkills()));
            }
            if (dto.getProjects() != null) {
                entity.setProjectsJson(objectMapper.writeValueAsString(dto.getProjects()));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize resume JSON", e);
        }
    }
}
```

- [ ] **Step 4: 创建模板服务类**

```java
package top.javarem.omniAgent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.javarem.omniAgent.dto.TemplateDTO;
import top.javarem.omniAgent.entity.TemplateEntity;
import top.javarem.omniAgent.repository.TemplateRepository;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TemplateService {
    
    private final TemplateRepository templateRepository;
    
    @PostConstruct
    public void initDefaultTemplates() {
        if (templateRepository.count() == 0) {
            List<TemplateEntity> defaultTemplates = List.of(
                createTemplate("classic", "经典模板", true),
                createTemplate("modern", "现代模板", true),
                createTemplate("minimal", "简约模板", true)
            );
            templateRepository.saveAll(defaultTemplates);
        }
    }
    
    private TemplateEntity createTemplate(String id, String name, boolean active) {
        TemplateEntity template = new TemplateEntity();
        template.setId(id);
        template.setName(name);
        template.setActive(active);
        template.setThumbnail("/templates/" + id + ".png");
        template.setStylesJson("{}");
        return template;
    }
    
    public List<TemplateDTO> getActiveTemplates() {
        return templateRepository.findByActiveTrue().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
    
    private TemplateDTO toDTO(TemplateEntity entity) {
        TemplateDTO dto = new TemplateDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setThumbnail(entity.getThumbnail());
        return dto;
    }
}
```

- [ ] **Step 5: 验证服务层**

```bash
cd backend && mvn test -Dtest=ResumeServiceTest
```

---

#### 任务 2.3: REST 控制器
**Files:**
- Create: `backend/src/main/java/top/javarem/omniAgent/controller/ResumeController.java`
- Create: `backend/src/main/java/top/javarem/omniAgent/controller/TemplateController.java`

- [ ] **Step 1: 创建简历控制器**

```java
package top.javarem.omniAgent.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.javarem.omniAgent.dto.ResumeDTO;
import top.javarem.omniAgent.service.ResumeService;

import java.util.List;

@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ResumeController {
    
    private final ResumeService resumeService;
    
    @GetMapping
    public ResponseEntity<List<ResumeDTO>> getAllResumes() {
        return ResponseEntity.ok(resumeService.getAllResumes());
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ResumeDTO> getResumeById(@PathVariable String id) {
        return resumeService.getResumeById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PostMapping
    public ResponseEntity<ResumeDTO> createResume(@RequestBody ResumeDTO resume) {
        ResumeDTO created = resumeService.createResume(resume);
        return ResponseEntity.ok(created);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<ResumeDTO> updateResume(
            @PathVariable String id,
            @RequestBody ResumeDTO resume) {
        return resumeService.updateResume(id, resume)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteResume(@PathVariable String id) {
        if (resumeService.deleteResume(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
```

- [ ] **Step 2: 创建模板控制器**

```java
package top.javarem.omniAgent.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.javarem.omniAgent.dto.TemplateDTO;
import top.javarem.omniAgent.service.TemplateService;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TemplateController {
    
    private final TemplateService templateService;
    
    @GetMapping
    public ResponseEntity<List<TemplateDTO>> getTemplates() {
        return ResponseEntity.ok(templateService.getActiveTemplates());
    }
}
```

- [ ] **Step 3: 启动并测试 API**

```bash
cd backend && mvn spring-boot:run &
sleep 10
curl http://localhost:8080/api/templates
curl http://localhost:8080/api/resumes
```

预期：返回空数组 `[]` 表示 API 正常工作

- [ ] **Step 4: 测试创建简历**

```bash
curl -X POST http://localhost:8080/api/resumes \
  -H "Content-Type: application/json" \
  -d '{"title":"Test Resume","templateId":"classic"}'
```

预期：返回创建的简历对象，包含生成的 ID

---

### 阶段三：前端核心功能开发 (预计 4 天)

#### 任务 3.1: 简历编辑器组件
**Files:**
- Create: `frontend/src/components/ResumeEditor/PersonalInfoForm.tsx`
- Create: `frontend/src/components/ResumeEditor/EducationForm.tsx`
- Create: `frontend/src/components/ResumeEditor/ExperienceForm.tsx`
- Create: `frontend/src/components/ResumeEditor/SkillsForm.tsx`
- Create: `frontend/src/components/ResumeEditor/ProjectsForm.tsx`
- Create: `frontend/src/components/ResumeEditor/ResumeEditor.tsx`

- [ ] **Step 1: 创建个人信息表单组件**

```tsx
// frontend/src/components/ResumeEditor/PersonalInfoForm.tsx

import type { PersonalInfo } from '../../types/resume';

interface PersonalInfoFormProps {
  data: PersonalInfo;
  onChange: (data: PersonalInfo) => void;
}

export default function PersonalInfoForm({ data, onChange }: PersonalInfoFormProps) {
  const handleChange = (field: keyof PersonalInfo, value: string) => {
    onChange({ ...data, [field]: value });
  };

  return (
    <div className="space-y-4">
      <h3 className="text-lg font-semibold text-gray-800">个人信息</h3>
      
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            姓名
          </label>
          <input
            type="text"
            value={data.name}
            onChange={(e) => handleChange('name', e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            placeholder="张三"
          />
        </div>
        
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            邮箱
          </label>
          <input
            type="email"
            value={data.email}
            onChange={(e) => handleChange('email', e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            placeholder="zhangsan@example.com"
          />
        </div>
        
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            电话
          </label>
          <input
            type="tel"
            value={data.phone}
            onChange={(e) => handleChange('phone', e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            placeholder="138-0000-0000"
          />
        </div>
        
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            所在地
          </label>
          <input
            type="text"
            value={data.location}
            onChange={(e) => handleChange('location', e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            placeholder="北京市朝阳区"
          />
        </div>
      </div>
      
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          个人简介
        </label>
        <textarea
          value={data.summary}
          onChange={(e) => handleChange('summary', e.target.value)}
          rows={4}
          className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
          placeholder="简要介绍您的专业背景和职业目标..."
        />
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 创建教育经历表单组件**

```tsx
// frontend/src/components/ResumeEditor/EducationForm.tsx

import { Plus, Trash2 } from 'lucide-react';
import type { Education } from '../../types/resume';

interface EducationFormProps {
  data: Education[];
  onChange: (data: Education[]) => void;
}

export default function EducationForm({ data, onChange }: EducationFormProps) {
  const addEducation = () => {
    const newEducation: Education = {
      id: crypto.randomUUID(),
      school: '',
      degree: '',
      major: '',
      startDate: '',
      endDate: '',
    };
    onChange([...data, newEducation]);
  };

  const updateEducation = (id: string, field: keyof Education, value: string) => {
    onChange(data.map(item => 
      item.id === id ? { ...item, [field]: value } : item
    ));
  };

  const removeEducation = (id: string) => {
    onChange(data.filter(item => item.id !== id));
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold text-gray-800">教育经历</h3>
        <button
          onClick={addEducation}
          className="flex items-center gap-2 px-3 py-1 text-sm bg-blue-500 text-white rounded-md hover:bg-blue-600"
        >
          <Plus size={16} />
          添加
        </button>
      </div>

      {data.map((edu) => (
        <div key={edu.id} className="p-4 bg-gray-50 rounded-lg space-y-3">
          <div className="flex justify-end">
            <button
              onClick={() => removeEducation(edu.id)}
              className="text-red-500 hover:text-red-700"
            >
              <Trash2 size={18} />
            </button>
          </div>
          
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">学校</label>
              <input
                type="text"
                value={edu.school}
                onChange={(e) => updateEducation(edu.id, 'school', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="清华大学"
              />
            </div>
            
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">学位</label>
              <input
                type="text"
                value={edu.degree}
                onChange={(e) => updateEducation(edu.id, 'degree', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="学士/硕士/博士"
              />
            </div>
            
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">专业</label>
              <input
                type="text"
                value={edu.major}
                onChange={(e) => updateEducation(edu.id, 'major', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="计算机科学与技术"
              />
            </div>
            
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">GPA</label>
              <input
                type="text"
                value={edu.gpa || ''}
                onChange={(e) => updateEducation(edu.id, 'gpa', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="3.8/4.0"
              />
            </div>
            
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">开始时间</label>
              <input
                type="month"
                value={edu.startDate}
                onChange={(e) => updateEducation(edu.id, 'startDate', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">结束时间</label>
              <input
                type="month"
                value={edu.endDate}
                onChange={(e) => updateEducation(edu.id, 'endDate', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          </div>
        </div>
      ))}

      {data.length === 0 && (
        <p className="text-gray-500 text-center py-8">暂无教育经历，点击添加</p>
      )}
    </div>
  );
}
```

- [ ] **Step 3: 创建工作经历表单组件**

```tsx
// frontend/src/components/ResumeEditor/ExperienceForm.tsx

import { Plus, Trash2 } from 'lucide-react';
import type { Experience } from '../../types/resume';

interface ExperienceFormProps {
  data: Experience[];
  onChange: (data: Experience[]) => void;
}

export default function ExperienceForm({ data, onChange }: ExperienceFormProps) {
  const addExperience = () => {
    const newExp: Experience = {
      id: crypto.randomUUID(),
      company: '',
      position: '',
      location: '',
      startDate: '',
      endDate: '',
      description: '',
    };
    onChange([...data, newExp]);
  };

  const updateExperience = (id: string, field: keyof Experience, value: string) => {
    onChange(data.map(item => 
      item.id === id ? { ...item, [field]: value } : item
    ));
  };

  const removeExperience = (id: string) => {
    onChange(data.filter(item => item.id !== id));
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold text-gray-800">工作经历</h3>
        <button
          onClick={addExperience}
          className="flex items-center gap-2 px-3 py-1 text-sm bg-blue-500 text-white rounded-md hover:bg-blue-600"
        >
          <Plus size={16} />
          添加
        </button>
      </div>

      {data.map((exp) => (
        <div key={exp.id} className="p-4 bg-gray-50 rounded-lg space-y-3">
          <div className="flex justify-end">
            <button
              onClick={() => removeExperience(exp.id)}
              className="text-red-500 hover:text-red-700"
            >
              <Trash2 size={18} />
            </button>
          </div>
          
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">公司名称</label>
              <input
                type="text"
                value={exp.company}
                onChange={(e) => updateExperience(exp.id, 'company', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="阿里巴巴"
              />
            </div>
            
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">职位</label>
              <input
                type="text"
                value={exp.position}
                onChange={(e) => updateExperience(exp.id, 'position', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="高级前端工程师"
              />
            </div>
            
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">地点</label>
              <input
                type="text"
                value={exp.location}
                onChange={(e) => updateExperience(exp.id, 'location', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="杭州"
              />
            </div>
            
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">开始时间</label>
              <input
                type="month"
                value={exp.startDate}
                onChange={(e) => updateExperience(exp.id, 'startDate', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">结束时间</label>
              <input
                type="month"
                value={exp.endDate}
                onChange={(e) => updateExperience(exp.id, 'endDate', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="至今"
              />
            </div>
          </div>
          
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">工作描述</label>
            <textarea
              value={exp.description}
              onChange={(e) => updateExperience(exp.id, 'description', e.target.value)}
              rows={3}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="描述您的主要工作职责和成就..."
            />
          </div>
        </div>
      ))}

      {data.length === 0 && (
        <p className="text-gray-500 text-center py-8">暂无工作经历，点击添加</p>
      )}
    </div>
  );
}
```

- [ ] **Step 4: 创建技能表单组件**

```tsx
// frontend/src/components/ResumeEditor/SkillsForm.tsx

import { Plus, Trash2 } from 'lucide-react';
import type { SkillCategory } from '../../types/resume';

interface SkillsFormProps {
  data: SkillCategory[];
  onChange: (data: SkillCategory[]) => void;
}

export default function SkillsForm({ data, onChange }: SkillsFormProps) {
  const addCategory = () => {
    const newCategory: SkillCategory = {
      id: crypto.randomUUID(),
      category: '',
      items: [],
    };
    onChange([...data, newCategory]);
  };

  const updateCategory = (id: string, field: keyof SkillCategory, value: string | string[]) => {
    onChange(data.map(item => 
      item.id === id ? { ...item, [field]: value } : item
    ));
  };

  const updateSkills = (id: string, skillsText: string) => {
    const items = skillsText.split(/[,，]/).map(s => s.trim()).filter(Boolean);
    updateCategory(id, 'items', items);
  };

  const removeCategory = (id: string) => {
    onChange(data.filter(item => item.id !== id));
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold text-gray-800">技能特长</h3>
        <button
          onClick={addCategory}
          className="flex items-center gap-2 px-3 py-1 text-sm bg-blue-500 text-white rounded-md hover:bg-blue-600"
        >
          <Plus size={16} />
          添加类别
        </button>
      </div>

      {data.map((skill) => (
        <div key={skill.id} className="p-4 bg-gray-50 rounded-lg space-y-3">
          <div className="flex justify-between items-center">
            <input
              type="text"
              value={skill.category}
              onChange={(e) => updateCategory(skill.id, 'category', e.target.value)}
              className="text-lg font-medium px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="技能类别（如：编程语言）"
            />
            <button
              onClick={() => removeCategory(skill.id)}
              className="text-red-500 hover:text-red-700"
            >
              <Trash2 size={18} />
            </button>
          </div>
          
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              技能（用逗号分隔）
            </label>
            <textarea
              value={skill.items.join(', ')}
              onChange={(e) => updateSkills(skill.id, e.target.value)}
              rows={2}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="JavaScript, TypeScript, React..."
            />
          </div>
          
          {skill.items.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {skill.items.map((item, index) => (
                <span key={index} className="px-2 py-1 bg-blue-100 text-blue-800 text-sm rounded">
                  {item}
                </span>
              ))}
            </div>
          )}
        </div>
      ))}

      {data.length === 0 && (
        <p className="text-gray-500 text-center py-8">暂无技能，点击添加</p>
      )}
    </div>
  );
}
```

- [ ] **Step 5: 创建项目经历表单组件**

```tsx
// frontend/src/components/ResumeEditor/ProjectsForm.tsx

import { Plus, Trash2 } from 'lucide-react';
import type { Project } from '../../types/resume';

interface ProjectsFormProps {
  data: Project[];
  onChange: (data: Project[]) => void;
}

export default function ProjectsForm({ data, onChange }: ProjectsFormProps) {
  const addProject = () => {
    const newProject: Project = {
      id: crypto.randomUUID(),
      name: '',
      role: '',
      description: '',
      technologies: [],
      startDate: '',
      endDate: '',
    };
    onChange([...data, newProject]);
  };

  const updateProject = (id: string, field: keyof Project, value: string | string[]) => {
    onChange(data.map(item => 
      item.id === id ? { ...item, [field]: value } : item
    ));
  };

  const updateTechnologies = (id: string, techText: string) => {
    const items = techText.split(/[,，]/).map(s => s.trim()).filter(Boolean);
    updateProject(id, 'technologies', items);
  };

  const removeProject = (id: string) => {
    onChange(data.filter(item => item.id !== id));
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold text-gray-800">项目经历</h3>
        <button
          onClick={addProject}
          className="flex items-center gap-2 px-3 py-1 text-sm bg-blue-500 text-white rounded-md hover:bg-blue-600"
        >
          <Plus size={16} />
          添加
        </button>
      </div>

      {data.map((project) => (
        <div key={project.id} className="p-4 bg-gray-50 rounded-lg space-y-3">
          <div className="flex justify-end">
            <button
              onClick={() => removeProject(project.id)}
              className="text-red-500 hover:text-red-700"
            >
              <Trash2 size={18} />
            </button>
          </div>
          
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">项目名称</label>
              <input
                type="text"
                value={project.name}
                onChange={(e) => updateProject(project.id, 'name', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="企业管理系统"
              />
            </div>
            
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">项目角色</label>
              <input
                type="text"
                value={project.role}
                onChange={(e) => updateProject(project.id, 'role', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="前端开发负责人"
              />
            </div>
            
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">开始时间</label>
              <input
                type="month"
                value={project.startDate}
                onChange={(e) => updateProject(project.id, 'startDate', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">结束时间</label>
              <input
                type="month"
                value={project.endDate}
                onChange={(e) => updateProject(project.id, 'endDate', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          </div>
          
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">技术栈（用逗号分隔）</label>
            <input
              type="text"
              value={project.technologies.join(', ')}
              onChange={(e) => updateTechnologies(project.id, e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="React, Node.js, PostgreSQL"
            />
          </div>
          
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">项目描述</label>
            <textarea
              value={project.description}
              onChange={(e) => updateProject(project.id, 'description', e.target.value)}
              rows={3}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="描述项目背景、您的职责和主要成就..."
            />
          </div>
        </div>
      ))}

      {data.length === 0 && (
        <p className="text-gray-500 text-center py-8">暂无项目经历，点击添加</p>
      )}
    </div>
  );
}
```

- [ ] **Step 6: 创建主简历编辑器组件**

```tsx
// frontend/src/components/ResumeEditor/ResumeEditor.tsx

import { useState } from 'react';
import type { Resume } from '../../types/resume';
import { createEmptyResume } from '../../types/resume';
import PersonalInfoForm from './PersonalInfoForm';
import EducationForm from './EducationForm';
import ExperienceForm from './ExperienceForm';
import SkillsForm from './SkillsForm';
import ProjectsForm from './ProjectsForm';
import ResumePreview from '../ResumePreview/ResumePreview';
import TemplateGallery from '../TemplateGallery/TemplateGallery';

interface ResumeEditorProps {
  initialResume?: Resume | null;
  onSave: (resume: Resume) => void;
  onCancel: () => void;
}

type Tab = 'personal' | 'education' | 'experience' | 'skills' | 'projects' | 'template';

export default function ResumeEditor({ initialResume, onSave, onCancel }: ResumeEditorProps) {
  const [resume, setResume] = useState<Resume>(initialResume || createEmptyResume());
  const [activeTab, setActiveTab] = useState<Tab>('personal');

  const tabs: { id: Tab; label: string }[] = [
    { id: 'personal', label: '个人信息' },
    { id: 'education', label: '教育经历' },
    { id: 'experience', label: '工作经历' },
    { id: 'skills', label: '技能特长' },
    { id: 'projects', label: '项目经历' },
    { id: 'template', label: '选择模板' },
  ];

  const updatePersonalInfo = (personalInfo: Resume['personalInfo']) => {
    setResume({ ...resume, personalInfo });
  };

  const handleSave = () => {
    onSave(resume);
  };

  return (
    <div className="flex h-screen">
      {/* 左侧编辑区 */}
      <div className="w-1/2 overflow-y-auto p-6 bg-white border-r">
        <div className="mb-6">
          <input
            type="text"
            value={resume.title}
            onChange={(e) => setResume({ ...resume, title: e.target.value })}
            className="text-2xl font-bold w-full px-3 py-2 border-b-2 border-blue-500 focus:outline-none"
            placeholder="简历标题"
          />
        </div>

        {/* 标签导航 */}
        <div className="flex flex-wrap gap-2 mb-6 border-b pb-4">
          {tabs.map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`px-4 py-2 rounded-lg transition-colors ${
                activeTab === tab.id
                  ? 'bg-blue-500 text-white'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* 表单内容 */}
        <div className="space-y-6">
          {activeTab === 'personal' && (
            <PersonalInfoForm
              data={resume.personalInfo}
              onChange={updatePersonalInfo}
            />
          )}
          {activeTab === 'education' && (
            <EducationForm
              data={resume.education}
              onChange={(education) => setResume({ ...resume, education })}
            />
          )}
          {activeTab === 'experience' && (
            <ExperienceForm
              data={resume.experience}
              onChange={(experience) => setResume({ ...resume, experience })}
            />
          )}
          {activeTab === 'skills' && (
            <SkillsForm
              data={resume.skills}
              onChange={(skills) => setResume({ ...resume, skills })}
            />
          )}
          {activeTab === 'projects' && (
            <ProjectsForm
              data={resume.projects}
              onChange={(projects) => setResume({ ...resume, projects })}
            />
          )}
          {activeTab === 'template' && (
            <TemplateGallery
              selected={resume.templateId}
              onSelect={(templateId) => setResume({ ...resume, templateId })}
            />
          )}
        </div>

        {/* 操作按钮 */}
        <div className="flex justify-end gap-4 mt-8 pt-6 border-t">
          <button
            onClick={onCancel}
            className="px-6 py-2 text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200"
          >
            取消
          </button>
          <button
            onClick={handleSave}
            className="px-6 py-2 text-white bg-blue-500 rounded-lg hover:bg-blue-600"
          >
            保存简历
          </button>
        </div>
      </div>

      {/* 右侧预览区 */}
      <div className="w-1/2 bg-gray-100 overflow-y-auto p-6">
        <div className="bg-white shadow-lg rounded-lg overflow-hidden">
          <ResumePreview resume={resume} />
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 7: 运行测试验证**

```bash
cd frontend && npm run dev
```

访问 http://localhost:5173，点击创建简历按钮，进入编辑器页面

---

#### 任务 3.2: 简历模板和预览组件
**Files:**
- Create: `frontend/src/components/TemplateGallery/TemplateGallery.tsx`
- Create: `frontend/src/components/ResumePreview/ClassicTemplate.tsx`
- Create: `frontend/src/components/ResumePreview/ModernTemplate.tsx`
- Create: `frontend/src/components/ResumePreview/MinimalTemplate.tsx`
- Create: `frontend/src/components/ResumePreview/ResumePreview.tsx`

- [ ] **Step 1: 创建模板选择器组件**

```tsx
// frontend/src/components/TemplateGallery/TemplateGallery.tsx

import { Check } from 'lucide-react';

interface Template {
  id: string;
  name: string;
  thumbnail?: string;
}

const defaultTemplates: Template[] = [
  { id: 'classic', name: '经典模板' },
  { id: 'modern', name: '现代模板' },
  { id: 'minimal', name: '简约模板' },
];

interface TemplateGalleryProps {
  selected: string;
  onSelect: (templateId: string) => void;
}

export default function TemplateGallery({ selected, onSelect }: TemplateGalleryProps) {
  return (
    <div className="space-y-4">
      <h3 className="text-lg font-semibold text-gray-800">选择模板</h3>
      
      <div className="grid grid-cols-3 gap-4">
        {defaultTemplates.map((template) => (
          <div
            key={template.id}
            onClick={() => onSelect(template.id)}
            className={`relative cursor-pointer rounded-lg overflow-hidden border-2 transition-all ${
              selected === template.id
                ? 'border-blue-500 shadow-md'
                : 'border-gray-200 hover:border-gray-300'
            }`}
          >
            {/* 模板缩略图占位 */}
            <div className={`aspect-[3/4] flex items-center justify-center ${
              template.id === 'classic' ? 'bg-gradient-to-br from-blue-50 to-blue-100' :
              template.id === 'modern' ? 'bg-gradient-to-br from-purple-50 to-pink-100' :
              'bg-gradient-to-br from-gray-50 to-gray-100'
            }`}>
              <div className="text-center space-y-2 p-4">
                <div className="w-12 h-12 mx-auto bg-current opacity-20 rounded-full" />
                <div className="space-y-1">
                  <div className="w-20 h-2 bg-current opacity-20 mx-auto rounded" />
                  <div className="w-16 h-1.5 bg-current opacity-10 mx-auto rounded" />
                </div>
              </div>
            </div>
            
            <div className="p-3 text-center">
              <span className="text-sm font-medium text-gray-700">{template.name}</span>
            </div>
            
            {selected === template.id && (
              <div className="absolute top-2 right-2 w-6 h-6 bg-blue-500 rounded-full flex items-center justify-center">
                <Check size={14} className="text-white" />
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 创建经典模板**

```tsx
// frontend/src/components/ResumePreview/ClassicTemplate.tsx

import type { Resume } from '../../types/resume';

interface ClassicTemplateProps {
  resume: Resume;
}

export default function ClassicTemplate({ resume }: ClassicTemplateProps) {
  const { personalInfo, education, experience, skills, projects } = resume;

  return (
    <div className="p-8 font-serif" style={{ minHeight: '800px' }}>
      {/* 头部 */}
      <header className="text-center mb-8 border-b-2 border-gray-800 pb-6">
        <h1 className="text-3xl font-bold mb-2">{personalInfo.name || '姓名'}</h1>
        <div className="flex justify-center gap-4 text-sm text-gray-600">
          {personalInfo.email && <span>{personalInfo.email}</span>}
          {personalInfo.phone && <span>{personalInfo.phone}</span>}
          {personalInfo.location && <span>{personalInfo.location}</span>}
        </div>
      </header>

      {/* 个人简介 */}
      {personalInfo.summary && (
        <section className="mb-6">
          <h2 className="text-lg font-bold border-b border-gray-300 mb-3 pb-1">个人简介</h2>
          <p className="text-sm leading-relaxed">{personalInfo.summary}</p>
        </section>
      )}

      {/* 教育经历 */}
      {education.length > 0 && (
        <section className="mb-6">
          <h2 className="text-lg font-bold border-b border-gray-300 mb-3 pb-1">教育经历</h2>
          {education.map((edu) => (
            <div key={edu.id} className="mb-3">
              <div className="flex justify-between items-start">
                <div>
                  <span className="font-semibold">{edu.school}</span>
                  <span className="text-gray-600 ml-2">{edu.degree} | {edu.major}</span>
                </div>
                <span className="text-sm text-gray-500">
                  {edu.startDate} - {edu.endDate}
                </span>
              </div>
              {edu.gpa && <p className="text-sm text-gray-500 mt-1">GPA: {edu.gpa}</p>}
            </div>
          ))}
        </section>
      )}

      {/* 工作经历 */}
      {experience.length > 0 && (
        <section className="mb-6">
          <h2 className="text-lg font-bold border-b border-gray-300 mb-3 pb-1">工作经历</h2>
          {experience.map((exp) => (
            <div key={exp.id} className="mb-4">
              <div className="flex justify-between items-start">
                <div>
                  <span className="font-semibold">{exp.company}</span>
                  <span className="text-gray-600 ml-2">{exp.position}</span>
                </div>
                <span className="text-sm text-gray-500">
                  {exp.startDate} - {exp.endDate}
                </span>
              </div>
              <p className="text-sm mt-2 whitespace-pre-line">{exp.description}</p>
            </div>
          ))}
        </section>
      )}

      {/* 项目经历 */}
      {projects.length > 0 && (
        <section className="mb-6">
          <h2 className="text-lg font-bold border-b border-gray-300 mb-3 pb-1">项目经历</h2>
          {projects.map((project) => (
            <div key={project.id} className="mb-4">
              <div className="flex justify-between items-start">
                <div>
                  <span className="font-semibold">{project.name}</span>
                  <span className="text-gray-600 ml-2">{project.role}</span>
                </div>
                <span className="text-sm text-gray-500">
                  {project.startDate} - {project.endDate}
                </span>
              </div>
              {project.technologies.length > 0 && (
                <p className="text-sm text-blue-600 mt-1">
                  技术栈: {project.technologies.join(', ')}
                </p>
              )}
              <p className="text-sm mt-2 whitespace-pre-line">{project.description}</p>
            </div>
          ))}
        </section>
      )}

      {/* 技能特长 */}
      {skills.length > 0 && (
        <section>
          <h2 className="text-lg font-bold border-b border-gray-300 mb-3 pb-1">技能特长</h2>
          <div className="grid grid-cols-2 gap-2">
            {skills.map((skill) => (
              <div key={skill.id} className="text-sm">
                <span className="font-semibold">{skill.category}: </span>
                <span className="text-gray-600">{skill.items.join(', ')}</span>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
```

- [ ] **Step 3: 创建现代模板**

```tsx
// frontend/src/components/ResumePreview/ModernTemplate.tsx

import type { Resume } from '../../types/resume';

interface ModernTemplateProps {
  resume: Resume;
}

export default function ModernTemplate({ resume }: ModernTemplateProps) {
  const { personalInfo, education, experience, skills, projects } = resume;

  return (
    <div className="p-6 font-sans" style={{ minHeight: '800px', backgroundColor: '#f8fafc' }}>
      {/* 头部 - 现代化布局 */}
      <header className="mb-6 p-6 bg-gradient-to-r from-purple-600 to-blue-600 rounded-lg text-white">
        <h1 className="text-3xl font-bold mb-3">{personalInfo.name || '姓名'}</h1>
        
        <div className="flex flex-wrap gap-4 text-sm">
          {personalInfo.email && (
            <div className="flex items-center gap-1">
              <span>✉</span> {personalInfo.email}
            </div>
          )}
          {personalInfo.phone && (
            <div className="flex items-center gap-1">
              <span>☎</span> {personalInfo.phone}
            </div>
          )}
          {personalInfo.location && (
            <div className="flex items-center gap-1">
              <span>⌂</span> {personalInfo.location}
            </div>
          )}
        </div>
      </header>

      {/* 个人简介 */}
      {personalInfo.summary && (
        <section className="mb-6 p-4 bg-white rounded-lg shadow-sm">
          <h2 className="text-lg font-bold text-purple-700 mb-2">关于我</h2>
          <p className="text-sm text-gray-600 leading-relaxed">{personalInfo.summary}</p>
        </section>
      )}

      {/* 教育经历 */}
      {education.length > 0 && (
        <section className="mb-6">
          <h2 className="text-lg font-bold text-purple-700 mb-3 flex items-center gap-2">
            <span className="w-8 h-1 bg-purple-600 rounded"></span>
            教育背景
          </h2>
          <div className="space-y-3">
            {education.map((edu) => (
              <div key={edu.id} className="p-4 bg-white rounded-lg shadow-sm border-l-4 border-purple-500">
                <div className="flex justify-between items-start">
                  <div>
                    <span className="font-semibold text-lg">{edu.school}</span>
                    <span className="text-gray-500 ml-2">{edu.degree}</span>
                  </div>
                  <span className="text-sm text-purple-600 font-medium">
                    {edu.startDate} - {edu.endDate}
                  </span>
                </div>
                <p className="text-gray-600 mt-1">{edu.major}</p>
                {edu.gpa && <p className="text-sm text-gray-500 mt-1">GPA: {edu.gpa}</p>}
              </div>
            ))}
          </div>
        </section>
      )}

      {/* 工作经历 */}
      {experience.length > 0 && (
        <section className="mb-6">
          <h2 className="text-lg font-bold text-purple-700 mb-3 flex items-center gap-2">
            <span className="w-8 h-1 bg-purple-600 rounded"></span>
            工作经历
          </h2>
          <div className="space-y-3">
            {experience.map((exp) => (
              <div key={exp.id} className="p-4 bg-white rounded-lg shadow-sm">
                <div className="flex justify-between items-start">
                  <div>
                    <span className="font-semibold text-lg">{exp.company}</span>
                    <span className="text-purple-600 ml-2">{exp.position}</span>
                  </div>
                  <span className="text-sm text-gray-500">
                    {exp.startDate} - {exp.endDate}
                  </span>
                </div>
                <p className="text-sm mt-2 text-gray-600 whitespace-pre-line">{exp.description}</p>
              </div>
            ))}
          </div>
        </section>
      )}

      {/* 项目经历 */}
      {projects.length > 0 && (
        <section className="mb-6">
          <h2 className="text-lg font-bold text-purple-700 mb-3 flex items-center gap-2">
            <span className="w-8 h-1 bg-purple-600 rounded"></span>
            项目经历
          </h2>
          <div className="space-y-3">
            {projects.map((project) => (
              <div key={project.id} className="p-4 bg-white rounded-lg shadow-sm">
                <div className="flex justify-between items-start">
                  <div>
                    <span className="font-semibold text-lg">{project.name}</span>
                    <span className="text-purple-600 ml-2">{project.role}</span>
                  </div>
                  <span className="text-sm text-gray-500">
                    {project.startDate} - {project.endDate}
                  </span>
                </div>
                {project.technologies.length > 0 && (
                  <div className="flex flex-wrap gap-2 mt-2">
                    {project.technologies.map((tech, i) => (
                      <span key={i} className="px-2 py-1 bg-purple-100 text-purple-700 text-xs rounded-full">
                        {tech}
                      </span>
                    ))}
                  </div>
                )}
                <p className="text-sm mt-2 text-gray-600 whitespace-pre-line">{project.description}</p>
              </div>
            ))}
          </div>
        </section>
      )}

      {/* 技能特长 */}
      {skills.length > 0 && (
        <section>
          <h2 className="text-lg font-bold text-purple-700 mb-3 flex items-center gap-2">
            <span className="w-8 h-1 bg-purple-600 rounded"></span>
            技能特长
          </h2>
          <div className="grid grid-cols-2 gap-3">
            {skills.map((skill) => (
              <div key={skill.id} className="p-3 bg-white rounded-lg shadow-sm">
                <span className="font-semibold text-purple-700">{skill.category}</span>
                <p className="text-sm text-gray-600 mt-1">{skill.items.join(', ')}</p>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
```

- [ ] **Step 4: 创建简约模板**

```tsx
// frontend/src/components/ResumePreview/MinimalTemplate.tsx

import type { Resume } from '../../types/resume';

interface MinimalTemplateProps {
  resume: Resume;
}

export default function MinimalTemplate({ resume }: MinimalTemplateProps) {
  const { personalInfo, education, experience, skills, projects } = resume;

  return (
    <div className="p-8 font-sans bg-white" style={{ minHeight: '800px' }}>
      {/* 头部 */}
      <header className="mb-8">
        <h1 className="text-4xl font-light tracking-wide mb-4">
          {personalInfo.name || '姓名'}
        </h1>
        
        <div className="flex gap-6 text-sm text-gray-500">
          {personalInfo.email && <span>{personalInfo.email}</span>}
          {personalInfo.phone && <span>{personalInfo.phone}</span>}
          {personalInfo.location && <span>{personalInfo.location}</span>}
        </div>
      </header>

      {/* 个人简介 */}
      {personalInfo.summary && (
        <section className="mb-8">
          <p className="text-gray-600 leading-relaxed">{personalInfo.summary}</p>
        </section>
      )}

      {/* 教育经历 */}
      {education.length > 0 && (
        <section className="mb-8">
          <h2 className="text-xs font-bold uppercase tracking-widest text-gray-400 mb-4">
            Education
          </h2>
          {education.map((edu) => (
            <div key={edu.id} className="mb-4 last:mb-0">
              <div className="flex justify-between items-baseline">
                <div>
                  <span className="font-medium">{edu.school}</span>
                  <span className="text-gray-500 ml-2">{edu.degree}, {edu.major}</span>
                </div>
                <span className="text-sm text-gray-400">
                  {edu.startDate} — {edu.endDate}
                </span>
              </div>
            </div>
          ))}
        </section>
      )}

      {/* 工作经历 */}
      {experience.length > 0 && (
        <section className="mb-8">
          <h2 className="text-xs font-bold uppercase tracking-widest text-gray-400 mb-4">
            Experience
          </h2>
          {experience.map((exp) => (
            <div key={exp.id} className="mb-4 last:mb-0">
              <div className="flex justify-between items-baseline">
                <div>
                  <span className="font-medium">{exp.position}</span>
                  <span className="text-gray-500 ml-2">{exp.company}</span>
                </div>
                <span className="text-sm text-gray-400">
                  {exp.startDate} — {exp.endDate}
                </span>
              </div>
              <p className="text-sm text-gray-600 mt-1 whitespace-pre-line">{exp.description}</p>
            </div>
          ))}
        </section>
      )}

      {/* 项目经历 */}
      {projects.length > 0 && (
        <section className="mb-8">
          <h2 className="text-xs font-bold uppercase tracking-widest text-gray-400 mb-4">
            Projects
          </h2>
          {projects.map((project) => (
            <div key={project.id} className="mb-4 last:mb-0">
              <div className="flex justify-between items-baseline">
                <div>
                  <span className="font-medium">{project.name}</span>
                  {project.technologies.length > 0 && (
                    <span className="text-gray-400 ml-2 text-sm">
                      / {project.technologies.join(' ')}
                    </span>
                  )}
                </div>
                <span className="text-sm text-gray-400">
                  {project.startDate} — {project.endDate}
                </span>
              </div>
              <p className="text-sm text-gray-600 mt-1 whitespace-pre-line">{project.description}</p>
            </div>
          ))}
        </section>
      )}

      {/* 技能特长 */}
      {skills.length > 0 && (
        <section>
          <h2 className="text-xs font-bold uppercase tracking-widest text-gray-400 mb-4">
            Skills
          </h2>
          <p className="text-sm text-gray-600">
            {skills.map((skill) => `${skill.category}: ${skill.items.join(', ')}`).join(' / ')}
          </p>
        </section>
      )}
    </div>
  );
}
```

- [ ] **Step 5: 创建简历预览主组件**

```tsx
// frontend/src/components/ResumePreview/ResumePreview.tsx

import type { Resume } from '../../types/resume';
import ClassicTemplate from './ClassicTemplate';
import ModernTemplate from './ModernTemplate';
import MinimalTemplate from './MinimalTemplate';

interface ResumePreviewProps {
  resume: Resume;
}

export default function ResumePreview({ resume }: ResumePreviewProps) {
  const renderTemplate = () => {
    switch (resume.templateId) {
      case 'modern':
        return <ModernTemplate resume={resume} />;
      case 'minimal':
        return <MinimalTemplate resume={resume} />;
      case 'classic':
      default:
        return <ClassicTemplate resume={resume} />;
    }
  };

  return (
    <div className="resume-preview transform scale-[0.7] origin-top-left w-[142.86%]">
      {renderTemplate()}
    </div>
  );
}
```

- [ ] **Step 6: 验证模板预览**

访问编辑器页面，点击不同标签，验证实时预览效果

---

### 阶段四：PDF 导出功能 (预计 2 天)

#### 任务 4.1: PDF 导出服务
**Files:**
- Modify: `frontend/package.json` (添加 jsPDF 依赖)
- Create: `frontend/src/components/ResumePreview/ResumePreview.tsx` (增强导出功能)
- Create: `frontend/src/services/pdfExport.ts`

- [ ] **Step 1: 添加 jsPDF 依赖**

```bash
cd frontend && npm install jspdf html2canvas
```

- [ ] **Step 2: 创建 PDF 导出服务**

```typescript
// frontend/src/services/pdfExport.ts

import jsPDF from 'jspdf';
import html2canvas from 'html2canvas';

export const exportToPDF = async (elementId: string, filename: string = 'resume.pdf') => {
  const element = document.getElementById(elementId);
  
  if (!element) {
    throw new Error('Preview element not found');
  }

  // 重置缩放以获取完整尺寸
  const originalTransform = element.style.transform;
  element.style.transform = 'scale(1)';
  element.style.width = '210mm';
  element.style.minHeight = '297mm';
  
  try {
    const canvas = await html2canvas(element, {
      scale: 2,
      useCORS: true,
      logging: false,
      backgroundColor: '#ffffff',
    });

    const imgData = canvas.toDataURL('image/png');
    const pdf = new jsPDF({
      orientation: 'portrait',
      unit: 'mm',
      format: 'a4',
    });

    const imgWidth = 210;
    const pageHeight = 297;
    const imgHeight = (canvas.height * imgWidth) / canvas.width;
    let heightLeft = imgHeight;
    let position = 0;

    pdf.addImage(imgData, 'PNG', 0, position, imgWidth, imgHeight);
    heightLeft -= pageHeight;

    while (heightLeft >= 0) {
      position = heightLeft - imgHeight;
      pdf.addPage();
      pdf.addImage(imgData, 'PNG', 0, position, imgWidth, imgHeight);
      heightLeft -= pageHeight;
    }

    pdf.save(filename);
  } finally {
    // 恢复原始缩放
    element.style.transform = originalTransform;
    element.style.width = '';
    element.style.minHeight = '';
  }
};
```

- [ ] **Step 3: 创建预览页面**

```tsx
// frontend/src/pages/PreviewPage.tsx

import { Download, ArrowLeft } from 'lucide-react';
import type { Resume } from '../types/resume';
import { exportToPDF } from '../services/pdfExport';
import ClassicTemplate from '../components/ResumePreview/ClassicTemplate';
import ModernTemplate from '../components/ResumePreview/ModernTemplate';
import MinimalTemplate from '../components/ResumePreview/MinimalTemplate';

interface PreviewPageProps {
  resume: Resume;
  onBack: () => void;
}

export default function PreviewPage({ resume, onBack }: PreviewPageProps) {
  const handleExport = async () => {
    try {
      await exportToPDF('resume-print-area', `${resume.title || 'resume'}.pdf`);
    } catch (error) {
      console.error('Failed to export PDF:', error);
      alert('导出 PDF 失败，请重试');
    }
  };

  const renderTemplate = () => {
    switch (resume.templateId) {
      case 'modern':
        return <ModernTemplate resume={resume} />;
      case 'minimal':
        return <MinimalTemplate resume={resume} />;
      case 'classic':
      default:
        return <ClassicTemplate resume={resume} />;
    }
  };

  return (
    <div className="min-h-screen bg-gray-100">
      {/* 工具栏 */}
      <div className="sticky top-0 z-10 bg-white shadow-md px-6 py-4 flex items-center justify-between">
        <button
          onClick={onBack}
          className="flex items-center gap-2 text-gray-600 hover:text-gray-900"
        >
          <ArrowLeft size={20} />
          返回
        </button>
        
        <h1 className="text-lg font-semibold">{resume.title || '简历预览'}</h1>
        
        <button
          onClick={handleExport}
          className="flex items-center gap-2 px-4 py-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600"
        >
          <Download size={18} />
          导出 PDF
        </button>
      </div>

      {/* 预览区域 */}
      <div className="p-8 flex justify-center">
        <div className="bg-white shadow-xl" style={{ width: '210mm' }}>
          <div id="resume-print-area">
            {renderTemplate()}
          </div>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: 验证 PDF 导出**

1. 启动前端应用
2. 创建或编辑一份简历
3. 点击预览按钮
4. 点击"导出 PDF"按钮
5. 检查 PDF 文件是否正确生成

---

### 阶段五：页面组件完善 (预计 1 天)

#### 任务 5.1: 首页和编辑器页面
**Files:**
- Create: `frontend/src/pages/HomePage.tsx`
- Create: `frontend/src/pages/EditorPage.tsx`

- [ ] **Step 1: 创建首页组件**

```tsx
// frontend/src/pages/HomePage.tsx

import { Plus, Edit, Trash2, Eye } from 'lucide-react';
import type { Resume } from '../types/resume';

interface HomePageProps {
  resumes: Resume[];
  onCreate: () => void;
  onEdit: (resume: Resume) => void;
  onPreview: (resume: Resume) => void;
  onDelete: (id: string) => void;
}

export default function HomePage({ resumes, onCreate, onEdit, onPreview, onDelete }: HomePageProps) {
  const handleDelete = (id: string, title: string) => {
    if (confirm(`确定要删除简历"${title || '未命名'}"吗？`)) {
      onDelete(id);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100">
      {/* 头部 */}
      <header className="bg-white shadow-sm">
        <div className="max-w-6xl mx-auto px-6 py-6 flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">简历制作系统</h1>
            <p className="text-gray-500 mt-1">创建专业简历，让求职更简单</p>
          </div>
          <button
            onClick={onCreate}
            className="flex items-center gap-2 px-6 py-3 bg-blue-500 text-white rounded-lg hover:bg-blue-600 shadow-lg"
          >
            <Plus size={20} />
            创建简历
          </button>
        </div>
      </header>

      {/* 简历列表 */}
      <main className="max-w-6xl mx-auto px-6 py-8">
        {resumes.length === 0 ? (
          <div className="text-center py-16">
            <div className="w-24 h-24 mx-auto mb-6 bg-gray-200 rounded-full flex items-center justify-center">
              <span className="text-4xl">📄</span>
            </div>
            <h2 className="text-xl font-semibold text-gray-700 mb-2">还没有简历</h2>
            <p className="text-gray-500 mb-6">点击上方按钮创建您的第一份简历</p>
            <button
              onClick={onCreate}
              className="px-6 py-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600"
            >
              开始创建
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {resumes.map((resume) => (
              <div
                key={resume.id}
                className="bg-white rounded-xl shadow-md overflow-hidden hover:shadow-lg transition-shadow"
              >
                {/* 缩略图预览 */}
                <div className="h-48 bg-gradient-to-br from-gray-100 to-gray-200 flex items-center justify-center">
                  <div className="text-center">
                    <div className="w-16 h-16 mx-auto mb-2 bg-blue-500 rounded-full flex items-center justify-center">
                      <span className="text-white text-2xl font-bold">
                        {(resume.personalInfo?.name || 'A').charAt(0)}
                      </span>
                    </div>
                    <p className="text-gray-600 text-sm">
                      {resume.personalInfo?.name || '未填写姓名'}
                    </p>
                  </div>
                </div>

                {/* 信息 */}
                <div className="p-4">
                  <h3 className="font-semibold text-gray-800 truncate">
                    {resume.title || '未命名简历'}
                  </h3>
                  {resume.personalInfo?.email && (
                    <p className="text-sm text-gray-500 truncate mt-1">
                      {resume.personalInfo.email}
                    </p>
                  )}
                  <p className="text-xs text-gray-400 mt-2">
                    更新于 {new Date(resume.updatedAt).toLocaleDateString()}
                  </p>
                </div>

                {/* 操作按钮 */}
                <div className="border-t px-4 py-3 flex justify-between">
                  <button
                    onClick={() => onEdit(resume)}
                    className="flex items-center gap-1 text-sm text-blue-500 hover:text-blue-700"
                  >
                    <Edit size={16} />
                    编辑
                  </button>
                  <button
                    onClick={() => onPreview(resume)}
                    className="flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700"
                  >
                    <Eye size={16} />
                    预览
                  </button>
                  <button
                    onClick={() => handleDelete(resume.id, resume.title)}
                    className="flex items-center gap-1 text-sm text-red-500 hover:text-red-700"
                  >
                    <Trash2 size={16} />
                    删除
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
}
```

- [ ] **Step 2: 创建编辑器页面组件**

```tsx
// frontend/src/pages/EditorPage.tsx

import ResumeEditor from '../components/ResumeEditor/ResumeEditor';
import type { Resume } from '../types/resume';

interface EditorPageProps {
  resume: Resume | null;
  onSave: (resume: Resume) => void;
  onCancel: () => void;
}

export default function EditorPage({ resume, onSave, onCancel }: EditorPageProps) {
  return (
    <div className="h-screen overflow-hidden">
      <ResumeEditor
        initialResume={resume}
        onSave={onSave}
        onCancel={onCancel}
      />
    </div>
  );
}
```

- [ ] **Step 3: 验证完整流程**

1. 启动前端和后端
2. 测试创建、编辑、预览、删除简历的完整流程
3. 测试 PDF 导出功能

---

### 阶段六：测试和优化 (预计 2 天)

#### 任务 6.1: 单元测试
**Files:**
- Create: `frontend/src/__tests__/resume.test.ts`
- Create: `backend/src/test/java/top/javarem/omniAgent/service/ResumeServiceTest.java`

- [ ] **Step 1: 创建前端测试文件**

```typescript
// frontend/src/__tests__/resume.test.ts

import { describe, it, expect } from 'vitest';
import { createEmptyResume } from '../types/resume';

describe('Resume Types', () => {
  it('should create empty resume with correct structure', () => {
    const resume = createEmptyResume();
    
    expect(resume).toHaveProperty('id');
    expect(resume).toHaveProperty('title');
    expect(resume).toHaveProperty('personalInfo');
    expect(resume).toHaveProperty('education');
    expect(resume).toHaveProperty('experience');
    expect(resume).toHaveProperty('skills');
    expect(resume).toHaveProperty('projects');
    expect(resume).toHaveProperty('templateId');
  });

  it('should have empty arrays for list fields', () => {
    const resume = createEmptyResume();
    
    expect(resume.education).toEqual([]);
    expect(resume.experience).toEqual([]);
    expect(resume.skills).toEqual([]);
    expect(resume.projects).toEqual([]);
  });

  it('should have default template', () => {
    const resume = createEmptyResume();
    
    expect(resume.templateId).toBe('classic');
  });
});
```

- [ ] **Step 2: 创建后端服务测试**

```java
// backend/src/test/java/top/javarem/omniAgent/service/ResumeServiceTest.java

package top.javarem.omniAgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.javarem.omniAgent.dto.ResumeDTO;
import top.javarem.omniAgent.repository.ResumeRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ResumeServiceTest {

    @Autowired
    private ResumeService resumeService;

    @Autowired
    private ResumeRepository resumeRepository;

    @Test
    void testCreateAndGetResume() {
        // 清理数据
        resumeRepository.deleteAll();

        // 创建简历
        ResumeDTO dto = new ResumeDTO();
        dto.setTitle("Test Resume");
        dto.setTemplateId("classic");
        
        Map<String, Object> personalInfo = new HashMap<>();
        personalInfo.put("name", "张三");
        personalInfo.put("email", "zhangsan@example.com");
        dto.setPersonalInfo(personalInfo);

        ResumeDTO created = resumeService.createResume(dto);
        assertNotNull(created.getId());
        assertEquals("Test Resume", created.getTitle());

        // 获取所有简历
        List<ResumeDTO> resumes = resumeService.getAllResumes();
        assertEquals(1, resumes.size());

        // 获取单个简历
        ResumeDTO found = resumeService.getResumeById(created.getId()).orElse(null);
        assertNotNull(found);
        assertEquals("张三", found.getPersonalInfo().get("name"));
    }

    @Test
    void testUpdateResume() {
        // 清理数据
        resumeRepository.deleteAll();

        // 创建简历
        ResumeDTO dto = new ResumeDTO();
        dto.setTitle("Original Title");
        dto.setTemplateId("classic");
        
        ResumeDTO created = resumeService.createResume(dto);
        
        // 更新简历
        created.setTitle("Updated Title");
        ResumeDTO updated = resumeService.updateResume(created.getId(), created).orElse(null);
        
        assertNotNull(updated);
        assertEquals("Updated Title", updated.getTitle());
    }

    @Test
    void testDeleteResume() {
        // 清理数据
        resumeRepository.deleteAll();

        // 创建简历
        ResumeDTO dto = new ResumeDTO();
        dto.setTitle("To Delete");
        dto.setTemplateId("classic");
        
        ResumeDTO created = resumeService.createResume(dto);
        
        // 删除简历
        boolean deleted = resumeService.deleteResume(created.getId());
        assertTrue(deleted);
        
        // 验证删除
        assertTrue(resumeService.getResumeById(created.getId()).isEmpty());
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
# 前端测试
cd frontend && npm test

# 后端测试
cd backend && mvn test
```

---

#### 任务 6.2: 性能优化和最终验证
**Files:**
- Modify: `frontend/vite.config.ts` (优化构建配置)
- Create: `backend/src/main/resources/data.sql` (初始化数据)

- [ ] **Step 1: 优化 Vite 配置**

```typescript
// frontend/vite.config.ts

import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['react', 'react-dom'],
          utils: ['lucide-react', 'clsx'],
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
```

- [ ] **Step 2: 创建数据库初始化脚本**

```sql
-- backend/src/main/resources/data.sql

-- 初始化默认模板
INSERT INTO templates (id, name, thumbnail, styles_json, active) VALUES
('classic', '经典模板', '/templates/classic.png', '{}', true),
('modern', '现代模板', '/templates/modern.png', '{}', true),
('minimal', '简约模板', '/templates/minimal.png', '{}', true);
```

- [ ] **Step 3: 最终验证**

1. 前后端联调测试
2. 响应式设计测试
3. 跨浏览器测试
4. 打包构建测试

```bash
# 前端构建
cd frontend && npm run build

# 后端打包
cd backend && mvn clean package -DskipTests
```

---

## 四、关键技术点详解

### 4.1 前后端通信
- 后端提供 RESTful API，前端通过 fetch 调用
- 使用 @CrossOrigin 处理跨域问题
- 请求/响应使用 JSON 格式

### 4.2 数据存储
- 使用 JPA 将 JSON 数据存储为 TEXT 类型
- 支持内存数据库 HSQLDB 开发环境
- 可切换到 MySQL/PostgreSQL 生产环境

### 4.3 PDF 导出原理
1. 使用 html2canvas 将 DOM 元素转换为 Canvas
2. 将 Canvas 转换为 PNG 图片
3. 使用 jsPDF 创建 A4 PDF 并嵌入图片
4. 处理多页情况

### 4.4 模板系统设计
- 每个模板对应一个 React 组件
- 模板共享相同的简历数据结构
- 通过 templateId 选择渲染哪个模板

---

## 五、部署说明

### 5.1 前端部署
```bash
cd frontend
npm run build
# 将 dist 目录部署到 Nginx 或 CDN
```

### 5.2 后端部署
```bash
cd backend
mvn clean package -DskipTests
java -jar target/omniAgent-1.0-SNAPSHOT.jar
```

### 5.3 环境变量
```yaml
# 后端 application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/resume_db
    username: root
    password: ${DB_PASSWORD}
```

---

## 六、后续扩展功能建议

1. **用户系统** - 添加注册登录，支持多用户
2. **模板市场** - 用户可上传分享模板
3. **AI 辅助** - 使用 AI 优化简历内容
4. **在线分享** - 生成简历分享链接
5. **多语言支持** - 支持英文简历
6. **简历分析** - ATS 兼容性检测

---

**Plan created:** 2025-01-20
**Estimated total development time:** 14 days
**Phase breakdown:**
- Phase 1: Project initialization (2 days)
- Phase 2: Backend API development (3 days)
- Phase 3: Frontend core features (4 days)
- Phase 4: PDF export functionality (2 days)
- Phase 5: Page components (1 day)
- Phase 6: Testing and optimization (2 days)
